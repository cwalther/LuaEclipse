/**
 * 
 */
package org.keplerproject.ldt.debug.core.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;

/**
 * @author jasonsantos
 */
public class LuaDebugServer {
//	String					fHost;
//	int						fControlPort;
//	int						fEventPort;
	LuaDebugServerConnection	fConnection;

	private LuaDebugElement	fElement;

	// sockets to communicate with VM
//	private ServerSocket	fRemdebugServer;
//	private Socket			fRemdebugSocket;
//	private Socket			fRemdebugEventSocket;
//	private PrintWriter		fRequestWriter;
//	private BufferedReader	fRequestReader;

	// reader for event data
//	private ServerSocket	fEventServer;
//	private BufferedReader	fEventReader;

//	private boolean			fStarted;
//	private boolean			fCouldntStart;
//	private boolean			fListening;

	private final Job		fRequestJob	= new Job("Starting debug server") {
		@Override
		protected IStatus run(IProgressMonitor monitor) {

			try {
				start();

				return Status.OK_STATUS;
			} catch (DebugException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}

			terminate();
			if (fElement != null) { //fElement may not have been created yet - this race condition is probably a bug and ignoring it not the correct solution
				fElement.getLuaDebugTarget().terminated();
			}
			return Status.CANCEL_STATUS;
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
			} /*catch (DebugException e) {
				System.out.println("Error receiving event:" + e.getMessage());
				target.terminated();
			}*/
			terminate();
			return Status.OK_STATUS;
		}
	};

	public LuaDebugServer(LuaDebugServerConnection connection)
			throws DebugException, IOException {

		//fControlPort = controlPort;
		//fEventPort = eventPort;
		//fHost = host;
		fConnection = connection;

		//fRequestJob.setSystem(true);
		fEventsJob.setSystem(true);

		fRequestJob.schedule();
	}

	/**
	 * @throws DebugException
	 */
	private void start() throws DebugException, IOException {

		try {
			fConnection.acceptRequestConnection();
		} catch (SocketTimeoutException e) { //and probably others...
//			fCouldntStart = true;
			throw e;
		}

//		fStarted = true;

		fConnection.acceptEventConnection();

		fEventsJob.schedule();
	}

	public void register(IDebugTarget target) throws DebugException {
		try {

			fElement = new LuaDebugElement(target);
			/*
			 * TODO create several event ports for multithreading debuggers
			 */
			fConnection.waitForRequestConnection();
			//FIXME what if it fails?
			
			//FIXME wait until the start job is accepting an event connection
			
			String result = fConnection.sendEventSubscriptionRequest();

			if (!result.startsWith("200"))
				throw new IOException("Can't connect to event port");
			
			fConnection.waitForEventConnection();

		} catch (UnknownHostException e) {
			fElement.requestFailed("Unable to connect to Remdebug", e);
		} catch (IOException e) {
			fElement.requestFailed("Unable to connect to Remdebug", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.core.model.IDebugElement#getModelIdentifier()
	 */

	public String getModelIdentifier() {
		return fElement.getDebugTarget().getModelIdentifier();
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
