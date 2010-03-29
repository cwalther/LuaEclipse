/**
 * 
 */
package org.keplerproject.ldt.debug.core.model;

import java.io.IOException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.keplerproject.ldt.debug.core.LuaDebuggerPlugin;

/**
 * @author jasonsantos
 */
public class LuaDebugServer {
	LuaDebugServerConnection	fConnection;

	private LuaDebugElement	fElement;

	private final Job		fRequestJob	= new Job("Waiting for RemDebug to connect") {
		@Override
		protected IStatus run(IProgressMonitor monitor) {

			try {
				start();

				return Status.OK_STATUS;
			} catch (IOException e) {
				e.printStackTrace();
				return new Status(IStatus.ERROR, LuaDebuggerPlugin.PLUGIN_ID, "Could not connect to RemDebug", e);
			} catch (InterruptedException e) {
				e.printStackTrace();
				return new Status(IStatus.ERROR, LuaDebuggerPlugin.PLUGIN_ID, "Could not connect to RemDebug", e);
			}
		}
	};
	
	private final Job		fSubscriptionJob = new Job("Sending subscription request") {
		@Override
		protected IStatus run(IProgressMonitor monitor) {
			String result = "";
			try {
				result = fConnection.sendEventSubscriptionRequest();
			} catch (IOException e) {
				return new Status(IStatus.ERROR, LuaDebuggerPlugin.PLUGIN_ID, "Error sending subscription request", e);
			}
			if (!result.startsWith("200")) {
				return new Status(IStatus.ERROR, LuaDebuggerPlugin.PLUGIN_ID, "Client couldn't process subscription request: " + result);
			}
			return Status.OK_STATUS;
		}
	};

	private final Job		fEventsJob	= new Job("Remdebug Event Dispatcher") {
		@Override
		protected IStatus run(IProgressMonitor monitor) {
			LuaDebugTarget target = null;

			try {
				String event = fConnection.receiveEvent();
				if (event == null) {
					terminate();
					return Status.CANCEL_STATUS;
				}
				
				// Thread.sleep(1000);
				target = fElement.getLuaDebugTarget();
				while ((target != null && !target.isTerminated())
						&& event != null) {

					if (event != null) {
						for (ILuaEventListener l : target.getEventListeners()) {
							l.handleEvent(event);
						}
					}

					if (!target.isTerminated())
						event = fConnection.receiveEvent();
				}
			} catch (IOException e) {
				target.terminated();
			}
			terminate();
			return Status.OK_STATUS;
		}
	};

	public LuaDebugServer(LuaDebugServerConnection connection) {

		fConnection = connection;

		fSubscriptionJob.setSystem(true);
		fEventsJob.setSystem(true);
	}
	
	public void acceptConnection() throws InterruptedException {
		fRequestJob.schedule();
		fRequestJob.join();
	}

	/**
	 * @throws IOException
	 * @throws InterruptedException 
	 */
	private void start() throws IOException, InterruptedException {
		
		//FIXME handle partial failure

		fConnection.acceptRequestConnection();
		
		// The subscription request should only be sent after we have started accepting the event connection - how to ensure this?? Running it in a separate job hopefully gets us a little closer, but the race condition still exists.
		fSubscriptionJob.schedule();

		fConnection.acceptEventConnection();
		
		fSubscriptionJob.join();

		fEventsJob.schedule();
	}

	public void register(IDebugTarget target) throws DebugException {
		assert fConnection.isConnected();
		fElement = new LuaDebugElement(target);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.core.model.IDebugElement#getModelIdentifier()
	 */

	public String getModelIdentifier() {
		return fElement.getDebugTarget().getModelIdentifier();
	}
	
	public boolean isConnected() {
		return fConnection.isConnected();
	}

	public String sendRequest(String request) throws IOException {
		return fConnection.sendRequest(request);
	}

	public void terminate() {
		fRequestJob.cancel();
		fEventsJob.cancel();
		try {
			fConnection.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
