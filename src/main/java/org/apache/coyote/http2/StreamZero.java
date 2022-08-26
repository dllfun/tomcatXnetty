package org.apache.coyote.http2;

import java.io.IOException;
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
import org.apache.coyote.RequestData;
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
	private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
	private final AtomicInteger nextLocalStreamId = new AtomicInteger(2);
	private volatile int newStreamsSinceLastPrune = 0;

	protected final String connectionId;

	public StreamZero(Http2UpgradeHandler handler) {
		super(STREAM_ID_ZERO);
		this.handler = handler;
		this.connectionId = Integer.toString(connectionIdGenerator.getAndIncrement());
		System.out.println("conn(" + connectionId + ")" + " created");
	}

	public Stream getStream(int streamId, boolean unknownIsError) throws ConnectionException {
		Integer key = Integer.valueOf(streamId);
		Stream result = streams.get(key);
		if (result == null && unknownIsError) {
			// Stream has been closed and removed from the map
			throw new ConnectionException(sm.getString("upgradeHandler.stream.closed", key), Http2Error.PROTOCOL_ERROR);
		}
		return result;
	}

	public Stream createRemoteStream(int streamId) throws ConnectionException {
		Integer key = Integer.valueOf(streamId);

		if (streamId % 2 != 1) {
			throw new ConnectionException(sm.getString("upgradeHandler.stream.even", key), Http2Error.PROTOCOL_ERROR);
		}

		pruneClosedStreams(streamId);

		Stream result = new Stream(key, handler);
		streams.put(key, result);
		return result;
	}

	public Stream createLocalStream(RequestData request) {
		int streamId = nextLocalStreamId.getAndAdd(2);

		Integer key = Integer.valueOf(streamId);

		Stream result = new Stream(key, handler, request);
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

		for (Entry<Integer, Stream> entry : streams.entrySet()) {
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

	public Map<Integer, Stream> getStreams() {
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
}
