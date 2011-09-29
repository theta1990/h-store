package edu.mit.hstore.dtxn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.apache.commons.collections15.set.ListOrderedSet;
import org.apache.log4j.Logger;
import org.voltdb.BatchPlanner;
import org.voltdb.ExecutionSite;
import org.voltdb.VoltTable;
import org.voltdb.messaging.FragmentTaskMessage;

import edu.brown.statistics.Histogram;
import edu.brown.utils.LoggerUtil;
import edu.brown.utils.LoggerUtil.LoggerBoolean;

public class ExecutionState {
    private static final Logger LOG = Logger.getLogger(LocalTransaction.class);
    private final static LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private final static LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    // ----------------------------------------------------------------------------
    // INTERNAL PARTITION+DEPENDENCY KEY
    // ----------------------------------------------------------------------------

    private static final int KEY_MAX_VALUE = 65535; // 2^16 - 1
    
    /**
     * Return a single key that encodes the partition id and dependency id
     * @param partition_id
     * @param dependency_id
     * @return
     */
    protected int createPartitionDependencyKey(int partition_id, int dependency_id) {
        Integer key = new Integer(partition_id | dependency_id<<16);
        this.partition_dependency_keys.add(key);
        int idx = this.partition_dependency_keys.indexOf(key);
        return (idx);
    }
    
    /**
     * For the given encoded Partition+DependencyInfo key, populate the given array
     * with the partitionid first, then the dependencyid second
     * @param key
     * @param values
     */
    protected void getPartitionDependencyFromKey(int idx, int values[]) {
        assert(values.length == 2);
        int key = this.partition_dependency_keys.get(idx).intValue();
        values[0] = key>>0 & KEY_MAX_VALUE;     // PartitionId
        values[1] = key>>16 & KEY_MAX_VALUE;    // DependencyId
    }
    
    // ----------------------------------------------------------------------------
    // GLOBAL DATA MEMBERS
    // ----------------------------------------------------------------------------
    
    /** The ExecutionSite that this TransactionState is tied to **/
    protected final ExecutionSite executor;
    
    /**
     * List of encoded Partition/Dependency keys
     */
    protected ListOrderedSet<Integer> partition_dependency_keys = new ListOrderedSet<Integer>();
    
    protected final Set<DependencyInfo> all_dependencies = new HashSet<DependencyInfo>();
    
    // ----------------------------------------------------------------------------
    // ROUND DATA MEMBERS
    // ----------------------------------------------------------------------------
    
    /**
     * This latch will block until all the Dependency results have returned
     * Generated in startRound()
     */
    protected CountDownLatch dependency_latch;
    
    /**
     * SQLStmt Index -> DependencyId -> DependencyInfo
     */
    protected final Map<Integer, DependencyInfo> dependencies[];
    
    /**
     * Final result output dependencies. Each position in the list represents a single Statement
     */
    protected final List<Integer> output_order = new ArrayList<Integer>();
    
    /**
     * As information come back to us, we need to keep track of what SQLStmt we are storing 
     * the data for. Note that we have to maintain two separate lists for results and responses
     * Partition-DependencyId Key Offset -> Next SQLStmt Index
     */
    protected final Map<Integer, Queue<Integer>> results_dependency_stmt_ctr = new ConcurrentHashMap<Integer, Queue<Integer>>();
    protected final Map<Integer, Queue<Integer>> responses_dependency_stmt_ctr = new ConcurrentHashMap<Integer, Queue<Integer>>();

    /**
     * Sometimes we will get responses/results back while we are still queuing up the rest of the tasks and
     * haven't started the next round. So we need a temporary space where we can put these guys until 
     * we start the round. Otherwise calculating the proper latch count is tricky
     */
    protected final Set<Integer> queued_responses = new ListOrderedSet<Integer>();
    protected final Map<Integer, VoltTable> queued_results = new ListOrderedMap<Integer, VoltTable>();
    
    /**
     * Blocked FragmentTaskMessages
     */
    protected final Set<FragmentTaskMessage> blocked_tasks = new HashSet<FragmentTaskMessage>();
    
    /**
     * Unblocked FragmentTaskMessages
     * The VoltProcedure thread will block on this queue waiting for tasks to execute inside of ExecutionSite
     * This has to be a set so that we make sure that we only submit a single message that contains all of the tasks to the Dtxn.Coordinator
     */
    protected final LinkedBlockingDeque<Collection<FragmentTaskMessage>> unblocked_tasks = new LinkedBlockingDeque<Collection<FragmentTaskMessage>>(); 
    
    /**
     * These are the DependencyIds that we don't bother returning to the ExecutionSite
     */
    protected final Set<Integer> internal_dependencies = new HashSet<Integer>();

    /**
     * Number of SQLStmts in the current batch
     */
    protected int batch_size = 0;
    /**
     * The total # of dependencies this Transaction is waiting for in the current round
     */
    protected int dependency_ctr = 0;
    /**
     * The total # of dependencies received thus far in the current round
     */
    protected int received_ctr = 0;
    
    /** 
     * What partitions has this txn touched
     */
    protected final Histogram<Integer> exec_touchedPartitions = new Histogram<Integer>();
    
    /**
     * 
     */
    protected final ConcurrentLinkedQueue<DependencyInfo> reusable_dependencies = new ConcurrentLinkedQueue<DependencyInfo>(); 
    
    // ----------------------------------------------------------------------------
    // INITIALIZATION
    // ----------------------------------------------------------------------------
    
    /**
     * Constructor
     */
    @SuppressWarnings("unchecked")
    public ExecutionState(ExecutionSite executor) {
        this.executor = executor;
        this.dependencies = (Map<Integer, DependencyInfo>[])new Map<?, ?>[BatchPlanner.MAX_BATCH_SIZE];
        for (int i = 0; i < this.dependencies.length; i++) {
            this.dependencies[i] = new HashMap<Integer, DependencyInfo>();
        } // FOR
    }
    
    public void clear() {
        this.exec_touchedPartitions.clear();
        this.dependency_latch = null;
        this.all_dependencies.clear();
        this.reusable_dependencies.clear();
        
        try {
            // Return all of our DependencyInfos
            for (DependencyInfo d : this.all_dependencies) {
                DependencyInfo.INFO_POOL.returnObject(d);
            } // FOR
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    
    // ----------------------------------------------------------------------------
    // EXECUTION ROUNDS
    // ----------------------------------------------------------------------------
    
    public void clearRound() {
        this.partition_dependency_keys.clear();
        this.output_order.clear();
        this.queued_responses.clear();
        this.queued_results.clear();
        this.blocked_tasks.clear();
        this.internal_dependencies.clear();

        // Note that we only want to clear the queues and not the whole maps
        for (Queue<Integer> q : this.results_dependency_stmt_ctr.values()) {
            q.clear();
        } // FOR
        for (Queue<Integer> q : this.responses_dependency_stmt_ctr.values()) {
            q.clear();
        } // FOR
        
        for (int i = 0; i < this.batch_size; i++) {
            this.reusable_dependencies.addAll(this.dependencies[i].values());
            this.dependencies[i].clear();
        } // FOR
        this.batch_size = 0;
        this.dependency_ctr = 0;
        this.received_ctr = 0;
    }
}
