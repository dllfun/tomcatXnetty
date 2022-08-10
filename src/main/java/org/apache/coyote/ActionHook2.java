/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.coyote;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ReadListener;
import javax.servlet.WriteListener;

import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.net.SocketEvent;

/**
 * Action hook. Actions represent the callback mechanism used by coyote servlet
 * containers to request operations on the coyote connectors. Some standard
 * actions are defined in ActionCode, however custom actions are permitted.
 *
 * The param object can be used to pass and return informations related with the
 * action.
 *
 *
 * This interface is typically implemented by ProtocolHandlers, and the param is
 * usually a Request or Response object.
 *
 * @author Remy Maucherat
 */
public interface ActionHook2 {

	/**
	 * Send an action to the connector.
	 *
	 * @param actionCode Type of the action
	 * @param param      Action parameter
	 */
	// public void action(ActionCode actionCode, Object param);

	public void commit();

	public void close();

	public void sendAck();

	public void clientFlush();

	// public void actionREQ_SET_BODY_REPLAY(ByteChunk param);

	public void isError(AtomicBoolean param);

	public void isIoAllowed(AtomicBoolean param);

	public void setErrorState(ErrorState errorState, Throwable t);

	public void closeNow(Object param);

	// public void disableSwallowInput();

	public void actionREQ_HOST_ADDR_ATTRIBUTE();

	public void actionREQ_HOST_ATTRIBUTE();

	public void actionREQ_LOCALPORT_ATTRIBUTE();

	public void actionREQ_LOCAL_ADDR_ATTRIBUTE();

	public void actionREQ_LOCAL_NAME_ATTRIBUTE();

	public void actionREQ_REMOTEPORT_ATTRIBUTE();

	public void actionREQ_SSL_ATTRIBUTE();

	public void actionREQ_SSL_CERTIFICATE();

	// public void actionASYNC_POST_PROCESS();

	// public void actionASYNC_DISPATCHED();

	// public void actionASYNC_RUN(Runnable param);

	// public void actionASYNC_DISPATCH();

	// public void actionASYNC_START(AsyncContextCallback param);

	// public void actionASYNC_COMPLETE();

	// public void actionASYNC_ERROR();

	// public void actionASYNC_IS_ASYNC(AtomicBoolean param);

	// public void actionASYNC_IS_COMPLETING(AtomicBoolean param);

	// public void actionASYNC_IS_DISPATCHING(AtomicBoolean param);

	// public void actionASYNC_IS_ERROR(AtomicBoolean param);

	// public void actionASYNC_IS_STARTED(AtomicBoolean param);

	// public void actionASYNC_IS_TIMINGOUT(AtomicBoolean param);

	// public void actionASYNC_SETTIMEOUT(Long param);

	// public void actionASYNC_TIMEOUT(AtomicBoolean param);

	// public void actionREQUEST_BODY_FULLY_READ(AtomicBoolean param);

	// public void actionNB_READ_INTEREST(AtomicBoolean param);

	public void processSocketEvent(SocketEvent event, boolean dispatch);

	public void actionNB_WRITE_INTEREST(AtomicBoolean param);

	public void clearDispatches();

	public void actionDISPATCH_READ();

	public void actionDISPATCH_WRITE();

	public void actionDISPATCH_EXECUTE();

	public void actionUPGRADE(UpgradeToken param);

	public void actionIS_PUSH_SUPPORTED(AtomicBoolean param);

	public void actionPUSH_REQUEST(RequestData param);

	public void actionIS_TRAILER_FIELDS_READY(AtomicBoolean param);

	public void actionIS_TRAILER_FIELDS_SUPPORTED(AtomicBoolean param);

	public void actionCONNECTION_ID(AtomicReference<Object> param);

	public void actionSTREAM_ID(AtomicReference<Object> param);

	// public ReadListener getReadListener();

	// public void setReadListener(ReadListener listener);

	// public WriteListener getWriteListener();

	// public void setWriteListener(WriteListener listener);

}
