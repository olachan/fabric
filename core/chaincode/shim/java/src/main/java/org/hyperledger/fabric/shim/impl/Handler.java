/*
Copyright DTCC, IBM 2016, 2017 All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.hyperledger.fabric.shim.impl;

import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.COMPLETED;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.DEL_STATE;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.ERROR;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.GET_QUERY_RESULT;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.GET_STATE;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.GET_STATE_BY_RANGE;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.INIT;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.INVOKE_CHAINCODE;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.PUT_STATE;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.QUERY_STATE_CLOSE;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.QUERY_STATE_NEXT;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.READY;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.REGISTERED;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.RESPONSE;
import static org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type.TRANSACTION;
import static org.hyperledger.fabric.shim.fsm.CallbackType.AFTER_EVENT;
import static org.hyperledger.fabric.shim.fsm.CallbackType.BEFORE_EVENT;
import static org.hyperledger.fabric.shim.impl.HandlerHelper.newCompletedEventMessage;
import static org.hyperledger.fabric.shim.impl.HandlerHelper.newErrorEventMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperledger.fabric.protos.common.Common.Status;
import org.hyperledger.fabric.protos.peer.Chaincode.ChaincodeID;
import org.hyperledger.fabric.protos.peer.Chaincode.ChaincodeInput;
import org.hyperledger.fabric.protos.peer.Chaincode.ChaincodeSpec;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.ChaincodeMessage.Type;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.GetQueryResult;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.GetStateByRange;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.PutStateInfo;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.QueryResponse;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.QueryStateClose;
import org.hyperledger.fabric.protos.peer.ChaincodeShim.QueryStateNext;
import org.hyperledger.fabric.protos.peer.ProposalResponsePackage.Response;
import org.hyperledger.fabric.shim.ChaincodeBase;
import org.hyperledger.fabric.shim.ChaincodeHelper;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.fsm.CBDesc;
import org.hyperledger.fabric.shim.fsm.Event;
import org.hyperledger.fabric.shim.fsm.EventDesc;
import org.hyperledger.fabric.shim.fsm.FSM;
import org.hyperledger.fabric.shim.fsm.exceptions.CancelledException;
import org.hyperledger.fabric.shim.fsm.exceptions.NoTransitionException;
import org.hyperledger.fabric.shim.helper.Channel;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import io.grpc.stub.StreamObserver;

public class Handler {

	private static Log logger = LogFactory.getLog(Handler.class);

	private StreamObserver<ChaincodeMessage> chatStream;
	private ChaincodeBase chaincode;

	private Map<String, Boolean> isTransaction;
	private Map<String, Channel<ChaincodeMessage>> responseChannel;
	public Channel<NextStateInfo> nextState;

	private FSM fsm;

	public Handler(StreamObserver<ChaincodeMessage> chatStream, ChaincodeBase chaincode) {
		this.chatStream = chatStream;
		this.chaincode = chaincode;

		responseChannel = new HashMap<String, Channel<ChaincodeMessage>>();
		isTransaction = new HashMap<String, Boolean>();
		nextState = new Channel<NextStateInfo>();

		fsm = new FSM("created");

		fsm.addEvents(
				//            Event Name              From           To
				new EventDesc(REGISTERED.toString(),  "created",     "established"),
				new EventDesc(READY.toString(),       "established", "ready"),
				new EventDesc(ERROR.toString(),       "init",        "established"),
				new EventDesc(RESPONSE.toString(),    "init",        "init"),
				new EventDesc(INIT.toString(),        "ready",       "ready"),
				new EventDesc(TRANSACTION.toString(), "ready",       "ready"),
				new EventDesc(RESPONSE.toString(),    "ready",       "ready"),
				new EventDesc(ERROR.toString(),       "ready",       "ready"),
				new EventDesc(COMPLETED.toString(),   "init",        "ready"),
				new EventDesc(COMPLETED.toString(),   "ready",       "ready")
				);

		fsm.addCallbacks(
				//         Type          Trigger                Callback
				new CBDesc(BEFORE_EVENT, REGISTERED.toString(), (event) -> beforeRegistered(event)),
				new CBDesc(AFTER_EVENT,  RESPONSE.toString(),   (event) -> afterResponse(event)),
				new CBDesc(AFTER_EVENT,  ERROR.toString(),      (event) -> afterError(event)),
				new CBDesc(BEFORE_EVENT, INIT.toString(),       (event) -> beforeInit(event)),
				new CBDesc(BEFORE_EVENT, TRANSACTION.toString(),(event) -> beforeTransaction(event))
				);
	}

	static String shortID(String uuid) {
		if (uuid.length() < 8) {
			return uuid;
		} else {
			return uuid.substring(0, 8);
		}
	}

	private void triggerNextState(ChaincodeMessage message, boolean send) {
		if (logger.isTraceEnabled()) logger.trace("triggerNextState for message " + message);
		nextState.add(new NextStateInfo(message, send));
	}

	public synchronized void serialSend(ChaincodeMessage message) {
		logger.debug("Sending message to peer: " + toJsonString(message));
		try {
			chatStream.onNext(message);
		} catch (Exception e) {
			logger.error(String.format("[%s]Error sending %s: %s", shortID(message), message.getType(), e));
			throw new RuntimeException(String.format("Error sending %s: %s", message.getType(), e));
		}
		if (logger.isTraceEnabled()) logger.trace("serialSend complete for message " + message);
	}

	private synchronized Channel<ChaincodeMessage> createChannel(String uuid) {
		if (responseChannel.containsKey(uuid)) {
			throw new IllegalStateException("[" + shortID(uuid) + "] Channel exists");
		}

		Channel<ChaincodeMessage> channel = new Channel<ChaincodeMessage>();
		responseChannel.put(uuid, channel);
		if (logger.isTraceEnabled()) logger.trace("channel created with uuid " + uuid);

		return channel;
	}

	private synchronized void sendChannel(ChaincodeMessage message) {
		if (!responseChannel.containsKey(message.getTxid())) {
			throw new IllegalStateException("[" + shortID(message) + "]sendChannel does not exist");
		}

		logger.debug(String.format("[%s]Before send", shortID(message)));
		responseChannel.get(message.getTxid()).add(message);
		logger.debug(String.format("[%s]After send", shortID(message)));
	}

	private ChaincodeMessage receiveChannel(Channel<ChaincodeMessage> channel) {
		try {
			return channel.take();
		} catch (InterruptedException e) {
			logger.debug("channel.take() failed with InterruptedException");

			// Channel has been closed?
			// TODO
			return null;
		}
	}

	private synchronized void deleteChannel(String uuid) {
		Channel<ChaincodeMessage> channel = responseChannel.remove(uuid);
		if (channel != null) {
			channel.close();
		}

		if (logger.isTraceEnabled()) logger.trace("deleteChannel done with uuid " + uuid);
	}

	/**
	 * Marks a UUID as either a transaction or a query
	 * 
	 * @param uuid
	 *            ID to be marked
	 * @param isTransaction
	 *            true for transaction, false for query
	 * @return whether or not the UUID was successfully marked
	 */
	private synchronized boolean markIsTransaction(String uuid, boolean isTransaction) {
		if (this.isTransaction == null) {
			return false;
		}

		this.isTransaction.put(uuid, isTransaction);
		return true;
	}

	private synchronized void deleteIsTransaction(String uuid) {
		isTransaction.remove(uuid);
	}

	private void beforeRegistered(Event event) {
		extractMessageFromEvent(event);
		logger.debug(String.format("Received %s, ready for invocations", REGISTERED));
	}

	/**
	 * Handles requests to initialize chaincode
	 * 
	 * @param message
	 *            chaincode to be initialized
	 */
	private void handleInit(ChaincodeMessage message) {
		new Thread(() -> {
			try {

				// Get the function and args from Payload
				final ChaincodeInput input = ChaincodeInput.parseFrom(message.getPayload());

				// Mark as a transaction (allow put/del state)
				markIsTransaction(message.getTxid(), true);

				// Create the ChaincodeStub which the chaincode can use to
				// callback
				final ChaincodeStub stub = new ChaincodeStubImpl(message.getTxid(), this, input.getArgsList());

				// Call chaincode's init
				final Response result = chaincode.init(stub);

				if (result.getStatus() == Status.SUCCESS_VALUE) {
					// Send COMPLETED with entire result as payload
					logger.debug(String.format(String.format("[%s]Init succeeded. Sending %s", shortID(message), COMPLETED)));
					triggerNextState(newCompletedEventMessage(message.getTxid(), result, stub.getEvent()), true);
				} else {
					// Send ERROR with entire result.Message as payload
					logger.error(String.format("[%s]Init failed. Sending %s", shortID(message), ERROR));
					triggerNextState(newErrorEventMessage(message.getTxid(), result.getMessage(), stub.getEvent()), true);
				}

			} catch (InvalidProtocolBufferException | RuntimeException e) {
				logger.error(String.format("[%s]Init failed. Sending %s", shortID(message), ERROR), e);
				triggerNextState(ChaincodeMessage.newBuilder()
						.setType(ERROR)
						.setPayload(Response.newBuilder()
							.setStatus(Status.INTERNAL_SERVER_ERROR_VALUE)
							.setPayload(ByteString.copyFromUtf8(e.toString()))
							.build().toByteString())
						.setTxid(message.getTxid())
						.build(), true);
			} finally {
				// delete isTransaction entry
				deleteIsTransaction(message.getTxid());
			}
		}).start();
	}

	// enterInitState will initialize the chaincode if entering init from established.
	private void beforeInit(Event event) {
		logger.debug(String.format("Before %s event.", event.name));
		logger.debug(String.format("Current state %s", fsm.current()));
		final ChaincodeMessage message = extractMessageFromEvent(event);
		logger.debug(String.format("[%s]Received %s, initializing chaincode", shortID(message), message.getType()));
		if (message.getType() == INIT) {
			// Call the chaincode's Run function to initialize
			handleInit(message);
		}
	}

	// handleTransaction Handles request to execute a transaction.
	private void handleTransaction(ChaincodeMessage message) {
		new Thread(() -> {
			try {

				// Get the function and args from Payload
				final ChaincodeInput input = ChaincodeInput.parseFrom(message.getPayload());

				// Mark as a transaction (allow put/del state)
				markIsTransaction(message.getTxid(), true);

				// Create the ChaincodeStub which the chaincode can use to
				// callback
				final ChaincodeStub stub = new ChaincodeStubImpl(message.getTxid(), this, input.getArgsList());

				// Call chaincode's invoke
				final Response result = chaincode.invoke(stub);

				if (result.getStatus() == Status.SUCCESS_VALUE) {
					// Send COMPLETED with entire result as payload
					logger.debug(String.format(String.format("[%s]Invoke succeeded. Sending %s", shortID(message), COMPLETED)));
					triggerNextState(newCompletedEventMessage(message.getTxid(), result, stub.getEvent()), true);
				} else {
					// Send ERROR with entire result.Message as payload
					logger.error(String.format("[%s]Invoke failed. Sending %s", shortID(message), ERROR));
					triggerNextState(newErrorEventMessage(message.getTxid(), result.getMessage(), stub.getEvent()), true);
				}

			} catch (InvalidProtocolBufferException | RuntimeException e) {
				logger.error(String.format("[%s]Invoke failed. Sending %s", shortID(message), ERROR), e);
				triggerNextState(ChaincodeMessage.newBuilder()
						.setType(ERROR)
						.setPayload(Response.newBuilder()
							.setStatus(Status.INTERNAL_SERVER_ERROR_VALUE)
							.setPayload(ByteString.copyFromUtf8(e.toString()))
							.build().toByteString())
						.setTxid(message.getTxid())
						.build(), true);
			} finally {
				// delete isTransaction entry
				deleteIsTransaction(message.getTxid());
			}
		}).start();
	}

	// enterTransactionState will execute chaincode's Run if coming from a TRANSACTION event.
	private void beforeTransaction(Event event) {
		ChaincodeMessage message = extractMessageFromEvent(event);
		logger.debug(String.format("[%s]Received %s, invoking transaction on chaincode(src:%s, dst:%s)", shortID(message), message.getType().toString(), event.src, event.dst));
		if (message.getType() == TRANSACTION) {
			// Call the chaincode's Run function to invoke transaction
			handleTransaction(message);
		}
	}

	// afterCompleted will need to handle COMPLETED event by sending message to the peer
	private void afterCompleted(Event event) {
		ChaincodeMessage message = extractMessageFromEvent(event);
		logger.debug(String.format("[%s]sending COMPLETED to validator for tid", shortID(message)));
		try {
			serialSend(message);
		} catch (Exception e) {
			event.cancel(new Exception("send COMPLETED failed %s", e));
		}
	}

	// afterResponse is called to deliver a response or error to the chaincode stub.
	private void afterResponse(Event event) {
		ChaincodeMessage message = extractMessageFromEvent(event);
		try {
			sendChannel(message);
			logger.debug(String.format("[%s]Received %s, communicated (state:%s)", shortID(message), message.getType(), fsm.current()));
		} catch (Exception e) {
			logger.error(String.format("[%s]error sending %s (state:%s): %s", shortID(message), message.getType(), fsm.current(), e));
		}
	}

	private ChaincodeMessage extractMessageFromEvent(Event event) {
		try {
			return (ChaincodeMessage) event.args[0];
		} catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
			final RuntimeException error = new RuntimeException("No chaincode message found in event.", e);
			event.cancel(error);
			throw error;
		}
	}

	private void afterError(Event event) {
		ChaincodeMessage message = extractMessageFromEvent(event);
		/*
		 * TODO- revisit. This may no longer be needed with the
		 * serialized/streamlined messaging model There are two situations in
		 * which the ERROR event can be triggered:
		 * 
		 * 1. When an error is encountered within handleInit or
		 * handleTransaction - some issue at the chaincode side; In this case
		 * there will be no responseChannel and the message has been sent to the
		 * validator.
		 * 
		 * 2. The chaincode has initiated a request (get/put/del state) to the
		 * validator and is expecting a response on the responseChannel; If
		 * ERROR is received from validator, this needs to be notified on the
		 * responseChannel.
		 */
		try {
			sendChannel(message);
		} catch (Exception e) {
			logger.debug(String.format("[%s]Error received from validator %s, communicated(state:%s)", shortID(message), message.getType(), fsm.current()));
		}
	}

	// handleGetState communicates with the validator to fetch the requested state information from the ledger.
	ByteString handleGetState(String key, String uuid) {
		try {
			// TODO Implement method to get and put entire state map and not one key at a time?
			// Create the channel on which to communicate the response from validating peer
			Channel<ChaincodeMessage> responseChannel;
			try {
				responseChannel = createChannel(uuid);
			} catch (Exception e) {
				logger.debug("Another state request pending for this Uuid. Cannot process.");
				throw e;
			}

			// Send GET_STATE message to validator chaincode support
			ChaincodeMessage message = ChaincodeMessage.newBuilder()
					.setType(GET_STATE)
					.setPayload(ByteString.copyFromUtf8(key))
					.setTxid(uuid)
					.build();

			logger.debug(String.format("[%s]Sending %s", shortID(message), GET_STATE));
			try {
				serialSend(message);
			} catch (Exception e) {
				logger.error(String.format("[%s]error sending GET_STATE %s", shortID(uuid), e));
				throw new RuntimeException("could not send message");
			}

			// Wait on responseChannel for response
			ChaincodeMessage response;
			try {
				response = receiveChannel(responseChannel);
			} catch (Exception e) {
				logger.error(String.format("[%s]Received unexpected message type", shortID(uuid)));
				throw new RuntimeException("Received unexpected message type");
			}

			// Success response
			if (response.getType() == RESPONSE) {
				logger.debug(String.format("[%s]GetState received payload %s", shortID(response.getTxid()), RESPONSE));
				return response.getPayload();
			}

			// Error response
			if (response.getType() == ERROR) {
				logger.error(String.format("[%s]GetState received error %s", shortID(response.getTxid()), ERROR));
				throw new RuntimeException(response.getPayload().toString());
			}

			// Incorrect chaincode message received
			logger.error(String.format("[%s]Incorrect chaincode message %s received. Expecting %s or %s", shortID(response.getTxid()), response.getType(), RESPONSE, ERROR));
			throw new RuntimeException("Incorrect chaincode message received");
		} finally {
			deleteChannel(uuid);
		}
	}

	private boolean isTransaction(String uuid) {
		return isTransaction.containsKey(uuid) && isTransaction.get(uuid);
	}

	void handlePutState(String key, ByteString value, String uuid) {
		// Check if this is a transaction
		logger.debug("[" + shortID(uuid) + "]Inside putstate (\"" + key + "\":\"" + value + "\"), isTransaction = " + isTransaction(uuid));

		if (!isTransaction(uuid)) {
			throw new IllegalStateException("Cannot put state in query context");
		}

		PutStateInfo payload = PutStateInfo.newBuilder()
				.setKey(key)
				.setValue(value)
				.build();

		// Create the channel on which to communicate the response from
		// validating peer
		Channel<ChaincodeMessage> responseChannel;
		try {
			responseChannel = createChannel(uuid);
		} catch (Exception e) {
			logger.error(String.format("[%s]Another state request pending for this Uuid. Cannot process.", shortID(uuid)));
			throw e;
		}

		// Defer
		try {
			// Send PUT_STATE message to validator chaincode support
			ChaincodeMessage message = ChaincodeMessage.newBuilder()
					.setType(PUT_STATE)
					.setPayload(payload.toByteString())
					.setTxid(uuid)
					.build();

			logger.debug(String.format("[%s]Sending %s", shortID(message), PUT_STATE));

			try {
				serialSend(message);
			} catch (Exception e) {
				logger.error(String.format("[%s]error sending PUT_STATE %s", message.getTxid(), e));
				throw new RuntimeException("could not send message");
			}

			// Wait on responseChannel for response
			ChaincodeMessage response;
			try {
				response = receiveChannel(responseChannel);
			} catch (Exception e) {
				// TODO figure out how to get uuid of receive channel
				logger.error(String.format("[%s]Received unexpected message type", e));
				throw e;
			}

			// Success response
			if (response.getType() == RESPONSE) {
				logger.debug(String.format("[%s]Received %s. Successfully updated state", shortID(response.getTxid()), RESPONSE));
				return;
			}

			// Error response
			if (response.getType() == ERROR) {
				logger.error(String.format("[%s]Received %s. Payload: %s", shortID(response.getTxid()), ERROR, response.getPayload()));
				throw new RuntimeException(response.getPayload().toStringUtf8());
			}

			// Incorrect chaincode message received
			logger.error(String.format("[%s]Incorrect chaincode message %s received. Expecting %s or %s", shortID(response.getTxid()), response.getType(), RESPONSE, ERROR));

			throw new RuntimeException("Incorrect chaincode message received");
		} catch (Exception e) {
			throw e;
		} finally {
			deleteChannel(uuid);
		}
	}

	void handleDeleteState(String key, String uuid) {
		// Check if this is a transaction
		if (!isTransaction(uuid)) {
			throw new RuntimeException("Cannot del state in query context");
		}

		// Create the channel on which to communicate the response from
		// validating peer
		Channel<ChaincodeMessage> responseChannel;
		try {
			responseChannel = createChannel(uuid);
		} catch (Exception e) {
			logger.error(String.format("[%s]Another state request pending for this Uuid." + " Cannot process create createChannel.", shortID(uuid)));
			throw e;
		}

		// Defer
		try {
			// Send DEL_STATE message to validator chaincode support
			ChaincodeMessage message = ChaincodeMessage.newBuilder()
					.setType(DEL_STATE)
					.setPayload(ByteString.copyFromUtf8(key))
					.setTxid(uuid)
					.build();
			logger.debug(String.format("[%s]Sending %s", shortID(uuid), DEL_STATE));
			try {
				serialSend(message);
			} catch (Exception e) {
				logger.error(String.format("[%s]error sending DEL_STATE %s", shortID(message), DEL_STATE));
				throw new RuntimeException("could not send message");
			}

			// Wait on responseChannel for response
			ChaincodeMessage response;
			try {
				response = receiveChannel(responseChannel);
			} catch (Exception e) {
				logger.error(String.format("[%s]Received unexpected message type", shortID(message)));
				throw new RuntimeException("Received unexpected message type");
			}

			if (response.getType() == RESPONSE) {
				// Success response
				logger.debug(String.format("[%s]Received %s. Successfully deleted state", message.getTxid(), RESPONSE));
				return;
			}

			if (response.getType() == ERROR) {
				// Error response
				logger.error(String.format("[%s]Received %s. Payload: %s", message.getTxid(), ERROR, response.getPayload()));
				throw new RuntimeException(response.getPayload().toStringUtf8());
			}

			// Incorrect chaincode message received
			logger.error(String.format("[%s]Incorrect chaincode message %s received. Expecting %s or %s", shortID(response.getTxid()), response.getType(), RESPONSE, ERROR));
			throw new RuntimeException("Incorrect chaincode message received");
		} finally {
			deleteChannel(uuid);
		}
	}

	QueryResponse handleGetStateByRange(String txId, String startKey, String endKey) {
		return invokeQueryResponseMessage(txId, GET_STATE_BY_RANGE, GetStateByRange.newBuilder()
				.setStartKey(startKey)
				.setEndKey(endKey)
				.build().toByteString());
	}

	QueryResponse queryStateNext(String txId, String queryId) {
		return invokeQueryResponseMessage(txId, QUERY_STATE_NEXT, QueryStateNext.newBuilder()
				.setId(queryId)
				.build().toByteString());
	}

	void queryStateClose(String txId, String queryId) {
		invokeQueryResponseMessage(txId, QUERY_STATE_CLOSE, QueryStateClose.newBuilder()
				.setId(queryId)
				.build().toByteString());
	}

	QueryResponse handleGetQueryResult(String txId, String query) {
		return invokeQueryResponseMessage(txId, GET_QUERY_RESULT, GetQueryResult.newBuilder()
				.setQuery(query)
				.build().toByteString());
	}

	QueryResponse handleGetHistoryForKey(String txId, String key) {
		return invokeQueryResponseMessage(txId, Type.GET_HISTORY_FOR_KEY, GetQueryResult.newBuilder()
				.setQuery(key)
				.build().toByteString());
	}

	private QueryResponse invokeQueryResponseMessage(String txId, ChaincodeMessage.Type type, ByteString payload) {
		try {
			return QueryResponse.parseFrom(invokeChaincodeSupport(txId, type, payload));
		} catch (InvalidProtocolBufferException e) {
			logger.error(String.format("[%-8s]unmarshall error", txId));
			throw new RuntimeException("Error unmarshalling QueryResponse.", e);
		}
	}

	private ByteString invokeChaincodeSupport(String txId, Type messageType, ByteString payload) {

		// Create the channel on which to communicate the response from
		// validating peer
		final Channel<ChaincodeMessage> responseChannel;
		try {
			responseChannel = createChannel(txId);
		} catch (Exception e) {
			logger.debug(String.format("[%s]Another state request pending for this Uuid." + " Cannot process.", shortID(txId)));
			throw e;
		}

		try {
			ChaincodeMessage message = ChaincodeMessage.newBuilder()
					.setType(messageType)
					.setPayload(payload)
					.setTxid(txId)
					.build();

			logger.debug(String.format("[%s]Sending %s", shortID(message), messageType));
			try {
				serialSend(message);
			} catch (Exception e) {
				logger.error(String.format("[%s]error sending %s", shortID(message), messageType));
				throw new RuntimeException("could not send message");
			}

			// Wait on responseChannel for response
			ChaincodeMessage response;
			try {
				response = receiveChannel(responseChannel);
			} catch (Exception e) {
				logger.error(String.format("[%s]Received unexpected message type", txId));
				throw new RuntimeException("Received unexpected message type");
			}

			if (response.getType() == RESPONSE) {
				// Success response
				logger.debug(String.format("[%s]Received %s. Successfully got range", shortID(response.getTxid()), RESPONSE));
				return response.getPayload();
			}

			if (response.getType() == ERROR) {
				// Error response
				logger.error(String.format("[%s]Received %s", shortID(response.getTxid()), ERROR));
				throw new RuntimeException(response.getPayload().toStringUtf8());
			}

			// Incorrect chaincode message received
			logger.error(String.format("Incorrect chaincode message %s recieved. Expecting %s or %s", response.getType(), RESPONSE, ERROR));
			throw new RuntimeException("Incorrect chaincode message received");
		} finally {
			deleteChannel(txId);
		}
	}

	Response handleInvokeChaincode(String chaincodeName, List<byte[]> args, String txid) {

		// create invocation specification of the chaincode to invoke
		final ChaincodeSpec invocationSpec = ChaincodeSpec.newBuilder()
				.setChaincodeId(ChaincodeID.newBuilder()
						.setName(chaincodeName)
						.build())
				.setInput(ChaincodeInput.newBuilder()
						.addAllArgs(args.stream().map(ByteString::copyFrom).collect(Collectors.toList()))
						.build())
				.build();

		try {
			// create the channel on which to communicate the response from
			// validating peer
			final Channel<ChaincodeMessage> responseChannel = createChannel(txid);

			// Send INVOKE_CHAINCODE message to validator chaincode support
			final ChaincodeMessage message = HandlerHelper.newInvokeChaincodeMessage(txid, invocationSpec.toByteString());
			logger.debug(String.format("[%s]Sending %s", shortID(message), INVOKE_CHAINCODE));
			serialSend(message);

			// wait for response chaincode message
			final ChaincodeMessage outerResponseMessage = receiveChannel(responseChannel);

			if (outerResponseMessage == null) {
				return ChaincodeHelper.newInternalServerErrorResponse("chaincode invoke returned null");
			}

			logger.debug(String.format("[%s]Received %s.", shortID(outerResponseMessage.getTxid()), outerResponseMessage.getType()));

			switch (outerResponseMessage.getType()) {
			case RESPONSE:
				// response message payload should be yet another chaincode
				// message (the actual response message)
				final ChaincodeMessage responseMessage = ChaincodeMessage.parseFrom(outerResponseMessage.getPayload());
				// the actual response message must be of type COMPLETED
				logger.debug(String.format("[%s]Received %s.", shortID(responseMessage.getTxid()), responseMessage.getType()));
				if (responseMessage.getType() == COMPLETED) {
					// success
					return Response.parseFrom(responseMessage.getPayload());
				} else {
					// error
					return ChaincodeHelper.newInternalServerErrorResponse(responseMessage.getPayload().toByteArray());
				}
			case ERROR:
				// Error response
				logger.error(String.format("[%s]Received %s.", shortID(outerResponseMessage.getTxid()), ERROR));
				return ChaincodeHelper.newInternalServerErrorResponse(outerResponseMessage.getPayload().toByteArray());
			default:
				// Incorrect chaincode message received
				logger.debug(String.format("[%s]Incorrect chaincode message %s received. Expecting %s or %s", shortID(outerResponseMessage.getTxid()), outerResponseMessage.getType(), RESPONSE, ERROR));
				return ChaincodeHelper.newInternalServerErrorResponse("Incorrect chaincode message received.", outerResponseMessage.toByteArray());
			}
		} catch (InvalidProtocolBufferException e) {
			return ChaincodeHelper.newInternalServerErrorResponse(e);
		} catch (RuntimeException e) {
			return ChaincodeHelper.newInternalServerErrorResponse(e);
		} finally {
			deleteChannel(txid);
		}
	}

	// handleMessage message handles loop for org.hyperledger.fabric.shim side
	// of chaincode/validator stream.
	public synchronized void handleMessage(ChaincodeMessage message) throws Exception {

		if (message.getType() == ChaincodeMessage.Type.KEEPALIVE) {
			logger.debug(String.format("[%s] Recieved KEEPALIVE message, do nothing", shortID(message)));
			// Received a keep alive message, we don't do anything with it for
			// now and it does not touch the state machine
			return;
		}

		logger.debug(String.format("[%s]Handling ChaincodeMessage of type: %s(state:%s)", shortID(message), message.getType(), fsm.current()));

		if (fsm.eventCannotOccur(message.getType().toString())) {
			String errStr = String.format("[%s]Chaincode handler org.hyperledger.fabric.shim.fsm cannot handle message (%s) with payload size (%d) while in state: %s", message.getTxid(), message.getType(), message.getPayload().size(), fsm.current());
			serialSend(newErrorEventMessage(message.getTxid(), errStr));
			throw new RuntimeException(errStr);
		}

		// Filter errors to allow NoTransitionError and CanceledError
		// to not propagate for cases where embedded Err == nil.
		try {
			fsm.raiseEvent(message.getType().toString(), message);
		} catch (NoTransitionException e) {
			if (e.error != null) throw e;
			logger.debug("[" + shortID(message) + "]Ignoring NoTransitionError");
		} catch (CancelledException e) {
			if (e.error != null) throw e;
			logger.debug("[" + shortID(message) + "]Ignoring CanceledError");
		}
	}

	private String shortID(ChaincodeMessage message) {
		return shortID(message.getTxid());
	}

	private static String toJsonString(ChaincodeMessage message) {
		try {
			return JsonFormat.printer().print(message);
		} catch (InvalidProtocolBufferException e) {
			return String.format("{ Type: %s, TxId: %s }", message.getType(), message.getTxid());
		}
	}

}
