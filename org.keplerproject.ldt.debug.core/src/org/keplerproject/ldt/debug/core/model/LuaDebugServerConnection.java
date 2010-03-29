package org.keplerproject.ldt.debug.core.model;

import java.io.IOException;

/**
 * 
 * @author Christian Walther <walther@indel.ch>, Indel AG
 *
 */
public abstract class LuaDebugServerConnection {
	
	public abstract void acceptRequestConnection() throws IOException;
	public abstract void acceptEventConnection() throws IOException;
	public abstract void close() throws IOException;
	public abstract boolean isConnected();
	
	public abstract String sendRequest(String request) throws IOException;
	public abstract String sendEventSubscriptionRequest() throws IOException;
	public abstract String receiveEvent() throws IOException;

}
