package io.shiftleft.overflowdb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * can clear references to disk and apply backpressure when creating new nodes, both to avoid an OutOfMemoryError
 *
 * can save all references to disk to persist the graph on shutdown
 * n.b. we could also persist the graph without a ReferenceManager, by serializing all nodes to disk. But if that
 * instance has been started from a storage location, the ReferenceManager ensures that we don't re-serialize all
 * unchanged nodes.
 */
public class ReferenceManager implements AutoCloseable, HeapUsageMonitor.HeapNotificationListener {
  private final Logger logger = LoggerFactory.getLogger(getClass());

  public final int releaseCount = 100000; //TODO make configurable
  private AtomicInteger totalReleaseCount = new AtomicInteger(0);
  private final Integer cpuCount = Runtime.getRuntime().availableProcessors();
  private final ExecutorService executorService = Executors.newFixedThreadPool(cpuCount);
  private int clearingProcessCount = 0;
  private final Object backPressureSyncObject = new Object();

  private final List<NodeRef> clearableRefs = Collections.synchronizedList(new LinkedList<>());

  public void registerRef(NodeRef ref) {
    clearableRefs.add(ref);
  }

  /**
   * when we're running low on heap memory we'll serialize some elements to disk. to ensure we're not creating new ones
   * faster than old ones are serialized away, we're applying some backpressure in those situation
   */
  public void applyBackpressureMaybe() {
    synchronized (backPressureSyncObject) {
      while (clearingProcessCount > 0) {
        try {
          logger.trace("wait until ref clearing completed");
          backPressureSyncObject.wait();
          logger.trace("continue");
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  @Override
  public void notifyHeapAboveThreshold() {
    if (clearingProcessCount > 0) {
      logger.debug("cleaning in progress, will only queue up more references to clear after that's completed");
    } else if (clearableRefs.isEmpty()) {
      logger.info("no refs to clear at the moment.");
    } else {
      int releaseCount = Integer.min(this.releaseCount, clearableRefs.size());
      logger.info("scheduled to clear " + releaseCount + " references (asynchronously)");
      asynchronouslyClearReferences(releaseCount);
    }
  }

  /**
   * run clearing of references asynchronously to not block the gc notification thread
   * using executor with one thread and capacity=1, drop `clearingInProgress` flag
   */
  private List<Future> asynchronouslyClearReferences(final int releaseCount) {
    List<Future> futures = new ArrayList<>(cpuCount);
    // use Math.ceil to err on the larger side
    final int releaseCountPerThread = (int) Math.ceil(releaseCount / cpuCount.floatValue());
    for (int i = 0; i < cpuCount; i++) {
      // doing this concurrently is tricky and won't be much faster since PriorityBlockingQueue is `blocking` anyway
      final List<NodeRef> refsToClear = collectRefsToClear(releaseCountPerThread);
      if (!refsToClear.isEmpty()) {
        futures.add(executorService.submit(() -> {
          safelyClearReferences(refsToClear);
          logger.info("completed clearing of " + refsToClear.size() + " references");
          logger.debug("current clearable queue size: " + clearableRefs.size());
          logger.debug("references cleared in total: " + totalReleaseCount);
        }));
      }
    }
    return futures;
  }

  private List<NodeRef> collectRefsToClear(int releaseCount) {
    final List<NodeRef> refsToClear = new ArrayList<>(releaseCount);

    while (releaseCount > 0) {
      if (clearableRefs.isEmpty()) {
        break;
      }
      final NodeRef ref = clearableRefs.remove(0);
      if (ref != null) {
        refsToClear.add(ref);
      }
      releaseCount--;
    }

    return refsToClear;
  }

  /**
   * clear references, ensuring no exception is raised
   */
  private void safelyClearReferences(final List<NodeRef> refsToClear) {
    try {
      synchronized (backPressureSyncObject) {
        clearingProcessCount += 1;
      }
      clearReferences(refsToClear);
    } catch (Exception e) {
      logger.error("error while trying to clear " + refsToClear.size() + " references", e);
    } finally {
      synchronized (backPressureSyncObject) {
        clearingProcessCount -= 1;
        if (clearingProcessCount == 0) {
          backPressureSyncObject.notifyAll();
        }
      }
    }
  }

  private void clearReferences(final List<NodeRef> refsToClear) throws IOException {
    logger.info("attempting to clear " + refsToClear.size() + " references");
    final Iterator<NodeRef> refsIterator = refsToClear.iterator();
    while (refsIterator.hasNext()) {
      final NodeRef ref = refsIterator.next();
      if (ref.isSet()) {
        ref.clear();
        totalReleaseCount.incrementAndGet();
      }
    }
  }

  /**
   * writes all references to disk overflow, blocks until complete.
   * useful when saving the graph
   */
  public void clearAllReferences() {
    while (!clearableRefs.isEmpty()) {
      int clearableRefsSize = clearableRefs.size();
      logger.info("clearing " + clearableRefsSize + " references - this may take some time");
      for (Future clearRefFuture : asynchronouslyClearReferences(clearableRefsSize)) {
        try {
          // block until everything is cleared
          clearRefFuture.get();
        } catch (Exception e) {
          throw new RuntimeException("error while clearing references to disk", e);
        }
      }
    }
  }

  @Override
  public void close() {
    executorService.shutdown();
  }

}
