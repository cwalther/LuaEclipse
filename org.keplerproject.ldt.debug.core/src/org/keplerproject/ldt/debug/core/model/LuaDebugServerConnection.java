package org.keplerproject.ldt.debug.core.model;

import java.io.IOException;

public abstract class LuaDebugServerConnection {
	
	protected boolean fRequestConnectionReady;
	protected boolean fEventConnectionReady;
	protected Object fRequestConnectionReadyCondition = new Object();
	protected Object fEventConnectionReadyCondition = new Object();
	
	public abstract void acceptRequestConnection() throws IOException;
	public abstract void acceptEventConnection() throws IOException;
	public abstract void close() throws IOException;
	
	public void waitForRequestConnection() {
		synchronized (fRequestConnectionReadyCondition) {
			while (!fRequestConnectionReady) {
				try {
					fRequestConnectionReadyCondition.wait();
				}
				catch (InterruptedException e) {}
			}
		}
	}
	public void waitForEventConnection() {
		synchronized (fEventConnectionReadyCondition) {
			while (!fEventConnectionReady) {
				try {
					fEventConnectionReadyCondition.wait();
				}
				catch (InterruptedException e) {}
			}
		}
	}
	
	public abstract String sendRequest(String request) throws IOException;
	public abstract String sendEventSubscriptionRequest() throws IOException;
	public abstract String receiveEvent() throws IOException;

}
