package org.keplerproject.ldt.debug.core.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 
 * @author Christian Walther <walther@indel.ch>, Indel AG
 *
 */
public class LuaDebugServerConnectionTCP extends LuaDebugServerConnection {

	int						fControlPort;
	int						fEventPort;
	
	private Socket			fRemdebugSocket;
	private Socket			fRemdebugEventSocket;
	private PrintWriter		fRequestWriter;
	private BufferedReader	fRequestReader;
	private BufferedReader	fEventReader;
	
	public LuaDebugServerConnectionTCP(int controlPort) {
		fControlPort = controlPort;
	}
	
	@Override
	public void acceptRequestConnection() throws IOException {
		assert fRemdebugSocket == null;
		ServerSocket remdebugServer = new ServerSocket(fControlPort);
		try {
			remdebugServer.setSoTimeout(5000);
			fRemdebugSocket = remdebugServer.accept();

			fRequestWriter = new PrintWriter(fRemdebugSocket.getOutputStream());
			fRequestReader = new BufferedReader(new InputStreamReader(fRemdebugSocket.getInputStream()));
		}
		finally {
			remdebugServer.close();
		}
	}

	@Override
	public void acceptEventConnection() throws IOException {
		assert fRemdebugEventSocket == null;
		fEventPort = findFreePort();
		ServerSocket eventServer = new ServerSocket(fEventPort);
		try {
			fRemdebugEventSocket = eventServer.accept();
		
			fEventReader = new BufferedReader(new InputStreamReader(fRemdebugEventSocket.getInputStream()));
		}
		finally {
			eventServer.close();
		}
	}

	@Override
	public void close() throws IOException {
		if (fRequestWriter != null) fRequestWriter.close();
		if (fRequestReader != null) fRequestReader.close();
		if (fRemdebugSocket != null) fRemdebugSocket.close();
		if (fEventReader != null) fEventReader.close();
		if (fRemdebugEventSocket != null) fRemdebugEventSocket.close();
		fRequestReader = null;
		fEventReader = null;
	}

	@Override
	public boolean isConnected() {
		return fRequestReader != null && fEventReader != null;
	}

	@Override
	public String sendEventSubscriptionRequest() throws IOException {
		return sendRequest("SUBSCRIBE " + fEventPort);
	}

	@Override
	public String sendRequest(String request) throws IOException {
		synchronized (fRemdebugSocket) {
			System.out.println(">>> " + request);
			fRequestWriter.println(request);
			fRequestWriter.flush();
			String response = fRequestReader.readLine();
			System.out.println("<<< " + response);
			return response;
		}
	}

	@Override
	public String receiveEvent() throws IOException {
		if (fEventReader != null) {
			String response = fEventReader.readLine();
			System.out.println("<-- " + response);
			return response;
		} else
			//FIXME does this occur?
			return "100 Continue";
	}

	private static int findFreePort() {
		ServerSocket socket = null;
		try {
			socket = new ServerSocket(0);
			return socket.getLocalPort();
		} catch (IOException e) {
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
				}
			}
		}
		return -1;
	}

}
