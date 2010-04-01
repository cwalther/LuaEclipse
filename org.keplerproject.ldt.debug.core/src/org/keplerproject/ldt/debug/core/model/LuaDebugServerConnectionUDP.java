package org.keplerproject.ldt.debug.core.model;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

public class LuaDebugServerConnectionUDP extends LuaDebugServerConnection {
	
	private int					fControlPort;
	private DatagramSocket		fRequestSocket;
	private DatagramSocket		fEventSocket;
	private Object				fEventSocketCondition = new Object();
	private byte				fSerialCounter = 0;

	private final int			DATAGRAM_LENGTH = 512;
	private final byte			DATAGRAM_TYPE_SYN = 1;
	private final byte			DATAGRAM_TYPE_ACK = 2;
	private final byte			DATAGRAM_TYPE_FIN = 3;
	private final byte			DATAGRAM_TYPE_DATA = 4;
	
	public LuaDebugServerConnectionUDP(int controlPort) {
		fControlPort = controlPort;
	}

	@Override
	public void acceptRequestConnection() throws IOException {
		fRequestSocket = new DatagramSocket(fControlPort);
		accept(fRequestSocket);
	}

	@Override
	public void acceptEventConnection() throws IOException {
		synchronized(fEventSocketCondition) {
			fEventSocket = new DatagramSocket();
			fEventSocketCondition.notify();
		}
		accept(fEventSocket);
	}

	@Override
	public void close() throws IOException {
		if (fRequestSocket != null) close(fRequestSocket);
		if (fEventSocket != null) close(fEventSocket);
		fRequestSocket = null;
		fEventSocket = null;
	}

	@Override
	public boolean isConnected() {
		return fRequestSocket != null && fEventSocket != null;
	}

	@Override
	public String sendRequest(String request) throws IOException {
		send(fRequestSocket, request, DATAGRAM_TYPE_DATA);
		return receive(fRequestSocket);
	}

	@Override
	public String sendEventSubscriptionRequest() throws IOException {
		// wait until acceptEventConnection() has created the socket so that we can get its port
		synchronized(fEventSocketCondition) {
			while (fEventSocket == null) {
				try {
					fEventSocketCondition.wait();
				} catch (InterruptedException e) {}
			}
		}
		return sendRequest("SUBSCRIBE " + fEventSocket.getLocalPort());
	}

	@Override
	public String receiveEvent() throws IOException {
		return receive(fEventSocket);
	}
	
	// Simple (but inefficient) mostly-reliable half-duplex protocol for
	// requests up to 129540 bytes over UDP:
	// The request is fragmented, every fragment is acknowledged by the
	// receiver, the receiver expects the fragments in order, the sender keeps
	// sending the same fragment until it is acknowledged and only then proceeds
	// to the next, both discard everything they don't expect.
	
	private void accept(DatagramSocket socket) throws IOException {
		byte[] inOutBytes = new byte[1];
		DatagramPacket inputPacket = new DatagramPacket(inOutBytes, inOutBytes.length);
		do {
			socket.receive(inputPacket);
		} while (!(inputPacket.getLength() >= 1 && inOutBytes[0] == DATAGRAM_TYPE_SYN));
		
		SocketAddress remoteAddress = inputPacket.getSocketAddress();
		socket.connect(remoteAddress);
		inOutBytes[0] = DATAGRAM_TYPE_ACK;
		socket.send(new DatagramPacket(inOutBytes, 1, remoteAddress));
	}
	
	private void send(DatagramSocket socket, String request, byte type) throws IOException {
		byte[] outputBytes = new byte[DATAGRAM_LENGTH];
		DatagramPacket outputPacket = new DatagramPacket(outputBytes, outputBytes.length, socket.getRemoteSocketAddress());
		byte[] inputBytes = new byte[4];
		DatagramPacket inputPacket = new DatagramPacket(inputBytes, inputBytes.length);
		
		byte serial = fSerialCounter++;
		byte[] requestBytes = request.getBytes("UTF-8");
		byte n = (byte)((requestBytes.length + (DATAGRAM_LENGTH-4) - 1)/(DATAGRAM_LENGTH-4));
		if (n == 0) n = 1;
		socket.setSoTimeout(2000);
		for (byte i = 0; i < n; i++) {
			outputBytes[0] = type;
			outputBytes[1] = i;
			outputBytes[2] = n;
			outputBytes[3] = serial;
			int fragmentlength = requestBytes.length - i*(DATAGRAM_LENGTH-4);
			if (fragmentlength > DATAGRAM_LENGTH-4) fragmentlength = DATAGRAM_LENGTH-4;
			System.arraycopy(requestBytes, i*(DATAGRAM_LENGTH-4), outputBytes, 4, fragmentlength);
			outputPacket.setLength(fragmentlength+4);
			socket.send(outputPacket);
			boolean ok = false;
			do {
				try {
					socket.receive(inputPacket);
					if (inputPacket.getLength() == 4
							&& inputBytes[0] == DATAGRAM_TYPE_ACK
							&& inputBytes[1] == i
							&& inputBytes[2] == n
							&& inputBytes[3] == serial) {
						ok = true;
					}
					// else we got something, but not the expected ACK, discard it and read again
					// (this can get us in an infinite loop if the other side is trying to send at the same time, but that shouldn't happen in our application)
				}
				catch (SocketTimeoutException e) {
					// got nothing within timeout, send again
					socket.send(outputPacket);
				}
			} while (!ok);
		}
	}
	
	private String receive(DatagramSocket socket) throws IOException {
		byte[] outputBytes = new byte[4];
		DatagramPacket outputPacket = new DatagramPacket(outputBytes, outputBytes.length, socket.getRemoteSocketAddress());
		byte[] inputBytes = new byte[DATAGRAM_LENGTH];
		DatagramPacket inputPacket = new DatagramPacket(inputBytes, inputBytes.length);
		
		socket.setSoTimeout(0);
		byte[] responseBytes = null;
		int responseLength = 0;
		boolean ok = false;
		boolean closed = false;
		byte n = 1;
		byte serial = 0;
		for (byte i = 0; i < n; i++) {
			ok = false;
			do {
				socket.receive(inputPacket);
				if (inputPacket.getLength() >= 4
						&& (inputBytes[0] == DATAGRAM_TYPE_DATA || inputBytes[0] == DATAGRAM_TYPE_FIN)
						&& inputBytes[1] == i) {
					if (i == 0) {
						n = inputBytes[2];
						serial = inputBytes[3];
						responseBytes = new byte[n*(DATAGRAM_LENGTH-4)];
						ok = true;
					}
					else {
						if (inputBytes[2] == n && inputBytes[3] == serial) {
							ok = true;
						}
					}
					closed = (ok && inputBytes[0] == DATAGRAM_TYPE_FIN);
				}
			} while (!ok);
			
			System.arraycopy(inputBytes, 4, responseBytes, responseLength, inputPacket.getLength()-4);
			responseLength += inputPacket.getLength()-4;
			
			outputBytes[0] = DATAGRAM_TYPE_ACK;
			outputBytes[1] = i;
			outputBytes[2] = n;
			outputBytes[3] = serial;
			socket.send(outputPacket);
		}
		
		if (closed) {
			socket.close();
			return null;
		}
		// We should ideally use the charset that Lua uses here, but we can't know that.
		//FIXME For UTF-8, the output is "unspecified" if the input is not valid UTF-8.
		// The best solution would probably be to try UTF-8 and if that fails fall back to Windows-1252 or ISO-8859-1.
		return new String(responseBytes, 0, responseLength, "UTF-8");
	}
	
	private void close(DatagramSocket socket) throws IOException {
		send(socket, "", DATAGRAM_TYPE_FIN);
		socket.close();
	}

}
