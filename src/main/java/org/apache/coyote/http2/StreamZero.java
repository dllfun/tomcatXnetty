package org.apache.coyote.http2;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.coyote.CloseNowException;
import org.apache.coyote.ExchangeData;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

public class StreamZero extends AbstractStream {

	protected static final Log log = LogFactory.getLog(StreamZero.class);
	protected static final StringManager sm = StringManager.getManager(Http2UpgradeHandler.class);
	private static final Integer STREAM_ID_ZERO = Integer.valueOf(0);

	private static final AtomicInteger connectionIdGenerator = new AtomicInteger(0);
	private final Http2UpgradeHandler handler;
	private AtomicReference<ConnectionState> connectionState = new AtomicReference<>(ConnectionState.NEW);
	private final Map<Integer, StreamChannel> streams = new ConcurrentHashMap<>();
	private final AtomicInteger nextLocalStreamId = new AtomicInteger(2);
	private volatile int newStreamsSinceLastPrune = 0;

	protected final String connectionId;

	public StreamZero(Http2UpgradeHandler handler) {
		super(STREAM_ID_ZERO);
		this.handler = handler;
		this.connectionId = Integer.toString(connectionIdGenerator.getAndIncrement());
		System.out.println("conn(" + connectionId + ")" + " created");
	}

	public StreamChannel getStream(int streamId, boolean unknownIsError) throws ConnectionException {
		Integer key = Integer.valueOf(streamId);
		StreamChannel result = streams.get(key);
		if (result == null && unknownIsError) {
			// Stream has been closed and removed from the map
			throw new ConnectionException(sm.getString("upgradeHandler.stream.closed", key), Http2Error.PROTOCOL_ERROR);
		}
		return result;
	}

	public StreamChannel createRemoteStream(int streamId) throws ConnectionException {
		Integer key = Integer.valueOf(streamId);

		if (streamId % 2 != 1) {
			throw new ConnectionException(sm.getString("upgradeHandler.stream.even", key), Http2Error.PROTOCOL_ERROR);
		}

		pruneClosedStreams(streamId);

		StreamChannel result = new StreamChannel(key, handler);
		streams.put(key, result);
		return result;
	}

	public StreamChannel createLocalStream(ExchangeData exchangeData) {
		int streamId = nextLocalStreamId.getAndAdd(2);

		Integer key = Integer.valueOf(streamId);

		StreamChannel result = new StreamChannel(key, handler, exchangeData);
		streams.put(key, result);
		return result;
	}

	private void pruneClosedStreams(int streamId) {
		// Only prune every 10 new streams
		if (newStreamsSinceLastPrune < 9) {
			// Not atomic. Increments may be lost. Not a problem.
			newStreamsSinceLastPrune++;
			return;
		}
		// Reset counter
		newStreamsSinceLastPrune = 0;

		// RFC 7540, 5.3.4 endpoints should maintain state for at least the
		// maximum number of concurrent streams
		long max = handler.getLocalSettings().getMaxConcurrentStreams();

		if (log.isDebugEnabled()) {
			log.debug(sm.getString("upgradeHandler.pruneStart", getConnectionId(), Long.toString(max),
					Integer.toString(streams.size())));
		}

		// Allow an additional 10% for closed streams that are used in the
		// priority tree
		max = max + max / 10;
		if (max > Integer.MAX_VALUE) {
			max = Integer.MAX_VALUE;
		}

		int toClose = streams.size() - (int) max;
		if (toClose < 1) {
			return;
		}

		// Need to try and close some streams.
		// Try to close streams in this order
		// 1. Completed streams used for a request with no children
		// 2. Completed streams used for a request with children
		// 3. Closed final streams
		//
		// Steps 1 and 2 will always be completed.
		// Step 3 will be completed to the minimum extent necessary to bring the
		// total number of streams under the limit.

		// Use these sets to track the different classes of streams
		TreeSet<Integer> candidatesStepOne = new TreeSet<>();
		TreeSet<Integer> candidatesStepTwo = new TreeSet<>();
		TreeSet<Integer> candidatesStepThree = new TreeSet<>();

		for (Entry<Integer, StreamChannel> entry : streams.entrySet()) {
			Stream stream = entry.getValue();
			// Never remove active streams
			if (stream.isActive()) {
				continue;
			}

			if (stream.isClosedFinal()) {
				// This stream went from IDLE to CLOSED and is likely to have
				// been created by the client as part of the priority tree.
				candidatesStepThree.add(entry.getKey());
			} else if (stream.getChildStreams().size() == 0) {
				// Closed, no children
				candidatesStepOne.add(entry.getKey());
			} else {
				// Closed, with children
				candidatesStepTwo.add(entry.getKey());
			}
		}

		// Process the step one list
		for (Integer streamIdToRemove : candidatesStepOne) {
			// Remove this childless stream
			Stream removedStream = streams.remove(streamIdToRemove);
			removedStream.detachFromParent();
			toClose--;
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.pruned", getConnectionId(), streamIdToRemove));
			}

			// Did this make the parent childless?
			AbstractStream parent = removedStream.getParentStream();
			while (parent instanceof Stream && !((Stream) parent).isActive() && !((Stream) parent).isClosedFinal()
					&& parent.getChildStreams().size() == 0) {
				streams.remove(parent.getIdentifier());
				parent.detachFromParent();
				toClose--;
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("upgradeHandler.pruned", getConnectionId(), streamIdToRemove));
				}
				// Also need to remove this stream from the p2 list
				candidatesStepTwo.remove(parent.getIdentifier());
				parent = parent.getParentStream();
			}
		}

		// Process the P2 list
		for (Integer streamIdToRemove : candidatesStepTwo) {
			removeStreamFromPriorityTree(streamIdToRemove);
			toClose--;
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.pruned", getConnectionId(), streamIdToRemove));
			}
		}

		while (toClose > 0 && candidatesStepThree.size() > 0) {
			Integer streamIdToRemove = candidatesStepThree.pollLast();
			removeStreamFromPriorityTree(streamIdToRemove);
			toClose--;
			if (log.isDebugEnabled()) {
				log.debug(sm.getString("upgradeHandler.prunedPriority", getConnectionId(), streamIdToRemove));
			}
		}

		if (toClose > 0) {
			log.warn(sm.getString("upgradeHandler.pruneIncomplete", getConnectionId(), Integer.toString(streamId),
					Integer.toString(toClose)));
		}
	}

	private void removeStreamFromPriorityTree(Integer streamIdToRemove) {
		Stream streamToRemove = streams.remove(streamIdToRemove);
		// Move the removed Stream's children to the removed Stream's
		// parent.
		Set<Stream> children = streamToRemove.getChildStreams();
		if (streamToRemove.getChildStreams().size() == 1) {
			// Shortcut
			streamToRemove.getChildStreams().iterator().next().rePrioritise(streamToRemove.getParentStream(),
					streamToRemove.getWeight());
		} else {
			int totalWeight = 0;
			for (Stream child : children) {
				totalWeight += child.getWeight();
			}
			for (Stream child : children) {
				streamToRemove.getChildStreams().iterator().next().rePrioritise(streamToRemove.getParentStream(),
						streamToRemove.getWeight() * child.getWeight() / totalWeight);
			}
		}
		streamToRemove.detachFromParent();
		streamToRemove.getChildStreams().clear();
	}

	@Override
	public boolean isClosed() {
		return connectionState.getAndSet(ConnectionState.CLOSED) == ConnectionState.CLOSED;
	}

	@Override
	public void close(Throwable e) {
		this.close();
	}

	@Override
	public void close() {
		ConnectionState previous = connectionState.getAndSet(ConnectionState.CLOSED);
		if (previous == ConnectionState.CLOSED) {
			// Already closed
			return;
		}

		for (Stream stream : streams.values()) {
			// The connection is closing. Close the associated streams as no
			// longer required (also notifies any threads waiting for allocations).
			stream.receiveReset(Http2Error.CANCEL.getCode());
		}
//		try {
//			handler.getChannel().close();//will closed by processor
//		} catch (Exception e) {
//			log.debug(sm.getString("upgradeHandler.socketCloseFailed"), e);
//		}
		System.out.println("conn(" + connectionId + ")" + " closed");
	}

	@Override
	protected final String getConnectionId() {
		return connectionId;
	}

	@Override
	protected final int getWeight() {
		return 0;
	}

	public AtomicReference<ConnectionState> getConnectionState() {
		return connectionState;
	}

	public Map<Integer, StreamChannel> getStreams() {
		return streams;
	}

	public enum ConnectionState {

		NEW(true), CONNECTED(true), PAUSING(true), PAUSED(false), CLOSED(false);

		private final boolean newStreamsAllowed;

		private ConnectionState(boolean newStreamsAllowed) {
			this.newStreamsAllowed = newStreamsAllowed;
		}

		public boolean isNewStreamAllowed() {
			return newStreamsAllowed;
		}
	}

	@SuppressWarnings("sync-override") // notify() needs to be outside sync
	// to avoid deadlock
	// @Override
	protected void incrementWindowSize(int increment) throws Http2Exception {
		Set<AbstractStream> streamsToNotify = null;

		synchronized (StreamZero.this) {
			long windowSize = StreamZero.this.getWindowSize();
			if (windowSize < 1 && windowSize + increment > 0) {
				streamsToNotify = releaseBackLog((int) (windowSize + increment));
			}
			StreamZero.super.incrementWindowSize(increment);
		}

		if (streamsToNotify != null) {
			for (AbstractStream stream : streamsToNotify) {
				if (log.isDebugEnabled()) {
					log.debug(sm.getString("upgradeHandler.releaseBacklog", StreamZero.this.getConnectionId(),
							stream.getIdentifier()));
				}
// There is never any O/P on stream zero but it is included in
// the backlog as it simplifies the code. Skip it if it appears
// here.
				if (StreamZero.this == stream) {
					continue;
				}
				((Stream) stream).notifyConnection();
			}
		}
	}

	private final Set<AbstractStream> backLogStreams = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private long backLogSize = 0;

	public int reserveWindowSize(Stream stream, int reservation, boolean block) throws IOException {
		// Need to be holding the stream lock so releaseBacklog() can't notify
		// this thread until after this thread enters wait()
		int allocation = 0;
		synchronized (stream) {
			synchronized (this) {
				if (!stream.canWrite()) {
					stream.doStreamCancel(sm.getString("upgradeHandler.stream.notWritable", stream.getConnectionId(),
							stream.getIdAsString()), Http2Error.STREAM_CLOSED);
				}
				long windowSize = getWindowSize();
				if (stream.getConnectionAllocationMade() > 0) {
					allocation = stream.getConnectionAllocationMade();
					stream.setConnectionAllocationMade(0);
				} else if (windowSize < 1) {
					// Has this stream been granted an allocation
					if (stream.getConnectionAllocationMade() == 0) {
						stream.setConnectionAllocationRequested(reservation);
						backLogSize += reservation;
						backLogStreams.add(stream);
						// Add the parents as well
						AbstractStream parent = stream.getParentStream();
						while (parent != null && backLogStreams.add(parent)) {
							parent = parent.getParentStream();
						}
					}
				} else if (windowSize < reservation) {
					allocation = (int) windowSize;
					decrementWindowSize(allocation);
				} else {
					allocation = reservation;
					decrementWindowSize(allocation);
				}
			}
			if (allocation == 0) {
				if (block) {
					try {
						// Connection level window is empty. Although this
						// request is for a stream, use the connection
						// timeout
						long writeTimeout = handler.getProtocol().getWriteTimeout();
						stream.waitForConnectionAllocation(writeTimeout);
						// Has this stream been granted an allocation
						if (stream.getConnectionAllocationMade() == 0) {
							String msg;
							Http2Error error;
							if (stream.isActive()) {
								if (log.isDebugEnabled()) {
									log.debug(sm.getString("upgradeHandler.noAllocation", connectionId,
											stream.getIdAsString()));
								}
								// No allocation
								// Close the connection. Do this first since
								// closing the stream will raise an exception.
								close();
								msg = sm.getString("stream.writeTimeout");
								error = Http2Error.ENHANCE_YOUR_CALM;
							} else {
								msg = sm.getString("stream.clientCancel");
								error = Http2Error.STREAM_CLOSED;
							}
							// Close the stream
							// This thread is in application code so need
							// to signal to the application that the
							// stream is closing
							stream.doStreamCancel(msg, error);
						} else {
							allocation = stream.getConnectionAllocationMade();
							stream.setConnectionAllocationMade(0);
						}
					} catch (InterruptedException e) {
						throw new IOException(sm.getString("upgradeHandler.windowSizeReservationInterrupted",
								connectionId, stream.getIdAsString(), Integer.toString(reservation)), e);
					}
				} else {
					stream.waitForConnectionAllocationNonBlocking();
					return 0;
				}
			}
		}
		return allocation;
	}

	private synchronized Set<AbstractStream> releaseBackLog(int increment) throws Http2Exception {
		Set<AbstractStream> result = new HashSet<>();
		int remaining = increment;
		if (backLogSize < remaining) {
			// Can clear the whole backlog
			for (AbstractStream stream : backLogStreams) {
				if (stream.getConnectionAllocationRequested() > 0) {
					stream.setConnectionAllocationMade(stream.getConnectionAllocationRequested());
					stream.setConnectionAllocationRequested(0);
				}
			}
			remaining -= backLogSize;
			backLogSize = 0;
			super.incrementWindowSize(remaining);

			result.addAll(backLogStreams);
			backLogStreams.clear();
		} else {
			allocate(this, remaining);
			Iterator<AbstractStream> streamIter = backLogStreams.iterator();
			while (streamIter.hasNext()) {
				AbstractStream stream = streamIter.next();
				if (stream.getConnectionAllocationMade() > 0) {
					backLogSize -= stream.getConnectionAllocationMade();
					backLogSize -= stream.getConnectionAllocationRequested();
					stream.setConnectionAllocationRequested(0);
					result.add(stream);
					streamIter.remove();
				}
			}
		}
		return result;
	}

	private int allocate(AbstractStream stream, int allocation) {
		if (log.isDebugEnabled()) {
			log.debug(sm.getString("upgradeHandler.allocate.debug", getConnectionId(), stream.getIdAsString(),
					Integer.toString(allocation)));
		}

		int leftToAllocate = allocation;

		if (stream.getConnectionAllocationRequested() > 0) {
			int allocatedThisTime;
			if (allocation >= stream.getConnectionAllocationRequested()) {
				allocatedThisTime = stream.getConnectionAllocationRequested();
			} else {
				allocatedThisTime = allocation;
			}
			stream.setConnectionAllocationRequested(stream.getConnectionAllocationRequested() - allocatedThisTime);
			stream.setConnectionAllocationMade(stream.getConnectionAllocationMade() + allocatedThisTime);
			leftToAllocate = leftToAllocate - allocatedThisTime;
		}

		if (leftToAllocate == 0) {
			return 0;
		}

		if (log.isDebugEnabled()) {
			log.debug(sm.getString("upgradeHandler.allocate.left", getConnectionId(), stream.getIdAsString(),
					Integer.toString(leftToAllocate)));
		}

		// Recipients are children of the current stream that are in the
		// backlog.
		Set<AbstractStream> recipients = new HashSet<>(stream.getChildStreams());
		recipients.retainAll(backLogStreams);

		// Loop until we run out of allocation or recipients
		while (leftToAllocate > 0) {
			if (recipients.size() == 0) {
				if (stream.getConnectionAllocationMade() == 0) {
					backLogStreams.remove(stream);
				}
				if (stream.getIdAsInt() == 0) {
					throw new IllegalStateException();
				}
				return leftToAllocate;
			}

			int totalWeight = 0;
			for (AbstractStream recipient : recipients) {
				if (log.isDebugEnabled()) {
					log.debug(
							sm.getString("upgradeHandler.allocate.recipient", getConnectionId(), stream.getIdAsString(),
									recipient.getIdAsString(), Integer.toString(recipient.getWeight())));
				}
				totalWeight += recipient.getWeight();
			}

			// Use an Iterator so fully allocated children/recipients can be
			// removed.
			Iterator<AbstractStream> iter = recipients.iterator();
			int allocated = 0;
			while (iter.hasNext()) {
				AbstractStream recipient = iter.next();
				int share = leftToAllocate * recipient.getWeight() / totalWeight;
				if (share == 0) {
					// This is to avoid rounding issues triggering an infinite
					// loop. It will cause a very slight over allocation but
					// HTTP/2 should cope with that.
					share = 1;
				}
				int remainder = allocate(recipient, share);
				// Remove recipients that receive their full allocation so that
				// they are excluded from the next allocation round.
				if (remainder > 0) {
					iter.remove();
				}
				allocated += (share - remainder);
			}
			leftToAllocate -= allocated;
		}

		return 0;
	}

	private static class BacklogTracker {

		private int remainingReservation;
		private int unusedAllocation;
		private boolean notifyInProgress;

		public BacklogTracker() {
		}

		public BacklogTracker(int reservation) {
			remainingReservation = reservation;
		}

		/**
		 * @return The number of bytes requiring an allocation from the Connection flow
		 *         control window
		 */
		public int getRemainingReservation() {
			return remainingReservation;
		}

		/**
		 *
		 * @return The number of bytes allocated from the Connection flow control window
		 *         but not yet written
		 */
		public int getUnusedAllocation() {
			return unusedAllocation;
		}

		/**
		 * The purpose of this is to avoid the incorrect triggering of a timeout for the
		 * following sequence of events:
		 * <ol>
		 * <li>window update 1</li>
		 * <li>allocation 1</li>
		 * <li>notify 1</li>
		 * <li>window update 2</li>
		 * <li>allocation 2</li>
		 * <li>act on notify 1 (using allocation 1 and 2)</li>
		 * <li>notify 2</li>
		 * <li>act on notify 2 (timeout due to no allocation)</li>
		 * </ol>
		 *
		 * @return {@code true} if a notify has been issued but the associated
		 *         allocation has not been used, otherwise {@code false}
		 */
		public boolean isNotifyInProgress() {
			return notifyInProgress;
		}

		public void useAllocation() {
			unusedAllocation = 0;
			notifyInProgress = false;
		}

		public void startNotify() {
			notifyInProgress = true;
		}

		private int allocate(int allocation) {
			if (remainingReservation >= allocation) {
				remainingReservation -= allocation;
				unusedAllocation += allocation;
				return 0;
			}

			int left = allocation - remainingReservation;
			unusedAllocation += remainingReservation;
			remainingReservation = 0;

			return left;
		}
	}
}
