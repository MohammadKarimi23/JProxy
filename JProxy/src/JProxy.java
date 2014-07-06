/*
 * One limitation of this implementation is that it doesn't 
 * close the ProxyThread socket if the client disconnects
 * or the server never responds, so you could end up with
 * a bunch of loose threads running amuck and waiting for
 * connections. As a band-aid, you can set the server socket
 * to timeout after a certain amount of time (use the
 * setTimeout() method in the ProxyThread class), although
 * this can cause false timeouts if a remote server is simply
 * slow to respond.
 *
 * Another thing is that it doesn't limit the number of
 * socket threads it will create, so if you use this on a
 * really busy machine that processed a bunch of requests,
 * you may have problems. You should use thread pools if
 * you're going to try something like this in a "real"
 * application.
 * 
 * nsftools.com
 */

import java.io.*;
import java.net.*;
import java.lang.reflect.Array;

public class JProxy extends Thread
{
	public static final int DEFAULT_PORT = 8080;
	
	private ServerSocket server = null;
	private int thisPort = DEFAULT_PORT;
	private String fwdServer = "";
	private int fwdPort = 0;
	private int ptTimeout = ProxyThread.DEFAULT_TIMEOUT;
	private int debugLevel = 0;
	private PrintStream debugOut = System.out;
	public Float transferRate; // by KB
	private static final int DEBUG_MODE = 0;
	
	/* here's a main method, in case you want to run this by itself */
	public static void main (String args[])
	{
		int port = 0;
		String fwdProxyServer = "";
		int fwdProxyPort = 0;
		
		if (args.length == 0)
		{
			System.err.println("USAGE: java jProxy <port number> [<fwd proxy> <fwd port>]");
			System.err.println("  <port number>   the port this service listens on");
			System.err.println("  <fwd proxy>     optional proxy server to forward requests to");
			System.err.println("  <fwd port>      the port that the optional proxy server is on");
			System.err.println("\nHINT: if you don't want to see all the debug information flying by,");
			System.err.println("you can pipe the output to a file or to 'nul' using \">\". For example:");
			System.err.println("  to send output to the file prox.txt: java jProxy 8080 > prox.txt");
			System.err.println("  to make the output go away: java jProxy 8080 > nul");
			return;
		}
		
		// get the command-line parameters
		port = Integer.parseInt(args[0]);
		if (args.length > 2)
		{
			fwdProxyServer = args[1];
			fwdProxyPort = Integer.parseInt(args[2]);
		}
		
		// create and start the jProxy thread, using a 20 second timeout
		// value to keep the threads from piling up too much
		System.err.println("  **  Starting jProxy on port " + port + ". Press CTRL-C to end.  **\n");
		JProxy jp = new JProxy(port, fwdProxyServer, fwdProxyPort, 20);
		jp.setDebug(DEBUG_MODE, System.out);		// or set the debug level to 2 for tons of output
		jp.start();
		
		// run forever; if you were calling this class from another
		// program and you wanted to stop the jProxy thread at some
		// point, you could write a loop that waits for a certain
		// condition and then calls jProxy.closeSocket() to kill
		// the running jProxy thread
//		while (true)
//		{
//			try {
//				Thread.sleep(3000);
//				System.out.println(jp.transferRate);
//			} catch (Exception e) {}
//		}
		
		// if we ever had a condition that stopped the loop above,
		// we'd want to do this to kill the running thread
		//jp.closeSocket();
		//return;
	}
	
	
	/* the proxy server just listens for connections and creates
	 * a new thread for each connection attempt (the ProxyThread
	 * class really does all the work)
	 */
	public JProxy (int port)
	{
		thisPort = port;
		transferRate = (float) 0;
	}
	
	public JProxy (int port, String proxyServer, int proxyPort)
	{
		this(port);
		fwdServer = proxyServer;
		fwdPort = proxyPort;
	}
	
	public JProxy (int port, String proxyServer, int proxyPort, int timeout)
	{
		this(port , proxyServer , proxyPort);	
		ptTimeout = timeout;
	}
	
	
	/* allow the user to decide whether or not to send debug
	 * output to the console or some other PrintStream
	 */
	public void setDebug (int level, PrintStream out)
	{
		debugLevel = level;
		debugOut = out;
	}
	
	/* get the port that we're supposed to be listening on
	 */
	public int getPort ()
	{
		return thisPort;
	}
	 
	
	/* return whether or not the socket is currently open
	 */
	public boolean isRunning ()
	{
		if (server == null)
			return false;
		else
			return true;
	}
	 
	
	/* closeSocket will close the open ServerSocket; use this
	 * to halt a running jProxy thread
	 */
	public void closeSocket ()
	{
		try {
			// close the open server socket
			server.close();
			// send it a message to make it stop waiting immediately
			// (not really necessary)
			/*Socket s = new Socket("localhost", thisPort);
			OutputStream os = s.getOutputStream();
			os.write((byte)0);
			os.close();
			s.close();*/
		}  catch(Exception e)  { 
			if (debugLevel > 0)
				debugOut.println(e);
		}
		
		server = null;
	}
	
	
	public void run()
	{
		try {
			// create a server socket, and loop forever listening for
			// client connections
			server = new ServerSocket(thisPort);
			if (debugLevel > 0)
				debugOut.println("Started jProxy on port " + thisPort);
			
			//===================================
			//MAKE THREAD FOR EACH REQUEST
			//===================================
			while (true)
			{
				Socket client = server.accept();
				ProxyThread t = new ProxyThread(client, fwdServer, fwdPort , this);
				t.setDebug(debugLevel, debugOut);
				t.setTimeout(ptTimeout);
				t.start();
			}
		}  catch (Exception e)  {
			if (debugLevel > 0)
				debugOut.println("jProxy Thread error: " + e);
		}
		closeSocket();
	}
	
	public void updateTransferRate(int transferRate) {
		this.transferRate += transferRate;
		System.out.println("Transfer: " + this.transferRate / 1000 + " KB");
	}
	
}


/* 
 * The ProxyThread will take an HTTP request from the client
 * socket and send it to either the server that the client is
 * trying to contact, or another proxy server
 */
class ProxyThread extends Thread
{
	private Socket pSocket;
	private String fwdServer = "";
	private int fwdPort = 0;
	private int debugLevel = 0;
	private PrintStream debugOut = System.out;
	private JProxy parent;
	
	// the socketTimeout is used to time out the connection to
	// the remote server after a certain period of inactivity;
	// the value is in milliseconds -- use zero if you don't want 
	// a timeout
	public static final int DEFAULT_TIMEOUT = 20 * 1000;
	private int socketTimeout = DEFAULT_TIMEOUT;
	
	public ProxyThread(Socket s , JProxy parent)
	{
		this.parent = parent;
		pSocket = s;
	}

	public ProxyThread(Socket s, String proxy, int port , JProxy parent )
	{
		this.parent = parent;		
		pSocket = s;
		fwdServer = proxy;
		fwdPort = port;
	}
	
	public void setTimeout (int timeout)
	{
		// assume that the user will pass the timeout value
		// in seconds (because that's just more intuitive)
		socketTimeout = timeout * 1000;
	}

	public void setDebug (int level, PrintStream out)
	{
		debugLevel = level;
		debugOut = out;
	}

	public void run()
	{
		try
		{
			long startTime = System.currentTimeMillis();
			
			// client streams (make sure you're using streams that use
			// byte arrays, so things like GIF and JPEG files and file
			// downloads will transfer properly)
			BufferedInputStream clientIn = new BufferedInputStream(pSocket.getInputStream());
			BufferedOutputStream clientOut = new BufferedOutputStream(pSocket.getOutputStream());
			
			// the socket to the remote server
			Socket server = null;
			
			// other variables
			byte[] request = null;
			byte[] response = null;
			int requestLength = 0;
			int responseLength = 0;
			int pos = -1;
			StringBuffer host = new StringBuffer("");
			String hostName = "";
			int hostPort = 80;
			
			// get the header info (the web browser won't disconnect after
			// it's sent a request, so make sure the waitForDisconnect
			// parameter is false)
			request = getHTTPData(clientIn , host, false);
			requestLength = Array.getLength(request);
			
			// separate the host name from the host port, if necessary
			// (like if it's "servername:8000")
			hostName = host.toString();
			pos = hostName.indexOf(":");
			if (pos > 0)// if hosname exists
			{
				try { 
					hostPort = Integer.parseInt(hostName.substring(pos + 1)); 
					}  catch (Exception e)  { }
				hostName = hostName.substring(0, pos);
			}
			
			//====================================================
			// SEND REQUST .....
			//====================================================
			
			// either forward this request to another proxy server or
			// send it straight to the Host
			try
			{
				if ((fwdServer.length() > 0) && (fwdPort > 0))
				{
					server = new Socket(fwdServer, fwdPort);
				}  else  {
					server = new Socket(hostName, hostPort);
				}
			}  catch (Exception e)  {
				// tell the client there was an error
				String errMsg = "HTTP/1.0 500\nContent Type: text/plain\n\n" + 
								"Error connecting to the server:\n" + e + "\n";
				clientOut.write(errMsg.getBytes(), 0, errMsg.length());
			}
			
			if (server != null)
			{
				server.setSoTimeout(socketTimeout);
				BufferedInputStream serverIn = new BufferedInputStream(server.getInputStream());
				BufferedOutputStream serverOut = new BufferedOutputStream(server.getOutputStream());
				
				// Send to host ...
				serverOut.write(request, 0, requestLength);
				serverOut.flush();
				
				// and get the response; if we're not at a debug level that
				// requires us to return the data in the response, just stream
				// it back to the client to save ourselves from having to
				// create and destroy an unnecessary byte array. Also, we
				// should set the waitForDisconnect parameter to 'true',
				// because some servers (like Google) don't always set the
				// Content-Length header field, so we have to listen until
				// they decide to disconnect (or the connection times out).
				
				//====================================================
				// GET RESPONSE AND FORWARD TO CLIENT ...
				//====================================================
				
				if (debugLevel > 1)
				{
					response = getHTTPData(serverIn, true); 
					responseLength = Array.getLength(response);
				}  else  {
					responseLength = streamHTTPData(serverIn, clientOut, true);
				}
				serverIn.close();
				serverOut.close();
			}
			
			// send the response back to the client, if we haven't already
			if (debugLevel > 1)
				clientOut.write(response, 0, responseLength);

			long endTime = System.currentTimeMillis();
			parent.updateTransferRate(responseLength);
			//====================================================
			// END SESSION ...
			//====================================================

			// if the user wants debug info, send them debug info; however,
			// keep in mind that because we're using threads, the output won't
			// necessarily be synchronous
			if (debugLevel > 0)
			{
				
				debugOut.println("Request from " + pSocket.getInetAddress().getHostAddress() + 
									" on Port " + pSocket.getLocalPort() + 
									" to host " + hostName + ":" + hostPort + 
									"\n  (" + requestLength + " bytes sent, " + 
									responseLength + " bytes returned, " + 
									Long.toString(endTime - startTime) + " ms elapsed)");
				debugOut.flush();
			}
			if (debugLevel > 1)
			{
				debugOut.println("REQUEST:\n" + (new String(request)));
				debugOut.println("RESPONSE:\n" + (new String(response)));
				debugOut.flush();
			}
			
			// close all the client streams so we can listen again
			clientOut.close();
			clientIn.close();
			pSocket.close();
		}  catch (Exception e)  {
			if (debugLevel > 0)
				debugOut.println("Error in ProxyThread: " + e);
			//e.printStackTrace();
		}
	}
	
	
	private byte[] getHTTPData (InputStream in, boolean waitForDisconnect)
	{
		// get the HTTP data from an InputStream, and return it as
		// a byte array
		// the waitForDisconnect parameter tells us what to do in case
		// the HTTP header doesn't specify the Content-Length of the
		// transmission
		StringBuffer foo = new StringBuffer("");
		return getHTTPData(in, foo, waitForDisconnect);
	}
	
	/**
	 * 
	 * @param in
	 * @param host
	 * @param waitForDisconnect
	 * @return
	 */
	private byte[] getHTTPData (InputStream in, StringBuffer host, boolean waitForDisconnect)
	{
		// get the HTTP data from an InputStream, and return it as
		// a byte array, and also return the Host entry in the header,
		// if it's specified -- note that we have to use a StringBuffer
		// for the 'host' variable, because a String won't return any
		// information when it's used as a parameter like that
		ByteArrayOutputStream bs = new ByteArrayOutputStream();
		streamHTTPData(in, bs, host, waitForDisconnect);
		return bs.toByteArray();
	}
	
	/**
	 * 
	 * @param in
	 * @param out
	 * @param waitForDisconnect
	 * @return
	 */
	private int streamHTTPData (InputStream in, OutputStream out, boolean waitForDisconnect)
	{
		StringBuffer foo = new StringBuffer("");
		return streamHTTPData(in, out, foo, waitForDisconnect);
	}
	
	/**
	 * 
	 * @param in
	 * @param out
	 * @param host
	 * @param waitForDisconnect
	 * @return header.length() + byteCount
	 */
	private int streamHTTPData (InputStream in, OutputStream out, 
									StringBuffer host, boolean waitForDisconnect)
	{
		// get the HTTP data from an InputStream, and send it to
		// the designated OutputStream
		// MK: Dealing with HTTP !!! From Client !!!
		
		StringBuffer header = new StringBuffer(""); 
		String data = "";
		int responseCode = 200;
		int contentLength = 0;
		int pos = -1;
		int byteCount = 0;
		//====================================================
		// GET HEADER DATA                          
		//====================================================
		try
		{
			// get the first line of the header, so we know the response code
			data = readLine(in);
			if (data != null)
			{
				header.append(data + "\r\n");
				pos = data.indexOf(" ");
				if ((data.toLowerCase().startsWith("http")) && 
					(pos >= 0) && (data.indexOf(" ", pos+1) >= 0))
				{
					String rcString = data.substring(pos+1, data.indexOf(" ", pos+1));
					try
					{
						responseCode = Integer.parseInt(rcString);// MK: ResponseCode ---> 404 Not Found / 200 Ok
					}  catch (Exception e)  {
						if (debugLevel > 0)
							debugOut.println("Error parsing response code " + rcString);
					}
				}
				else{ // NNNNNNNNNNNNNNNNNN
					int flag = data.indexOf(" ");
					System.err.println(data.substring(flag , data.indexOf(" " ,  flag + 1) ));
				}
			}
			
			// get the rest of the header info
			while ((data = readLine(in)) != null)
			{
				// the header ends at the first blank line
				if (data.length() == 0)
					break;
				header.append(data + "\r\n");
				
				// check for the Host header
				pos = data.toLowerCase().indexOf("host:");
				if (pos >= 0)
				{
					host.setLength(0);
					host.append(data.substring(pos + 5).trim());
				}
				
				// check for the Content-Length header
				pos = data.toLowerCase().indexOf("content-length:");
				if (pos >= 0)
					contentLength = Integer.parseInt(data.substring(pos + 15).trim());
			}
			
			//===================================================//
			// END OF HEADER
			//===================================================//
			
			// add a blank line to terminate the header info
			header.append("\r\n");
			
			// convert the header to a byte array, and write it to our stream
			out.write(header.toString().getBytes(), 0, header.length());
			
			// if the header indicated that this was not a 200 response(The request has succeeded),
			// just return what we've got if there is no Content-Length,
			// because we may not be getting anything else
			
			if ((responseCode != 200) && (contentLength == 0))
			{
				out.flush();
				return header.length();
			}
			
			//====================================
			//GET DATA, If any !!
			//====================================
			
			// get the body, if any; we try to use the Content-Length header to
			// determine how much data we're supposed to be getting, because 
			// sometimes the client/server won't disconnect after sending us
			// information...
			if (contentLength > 0)
				waitForDisconnect = false;
			
			if ((contentLength > 0) || (waitForDisconnect))
			{
				try {
					byte[] buf = new byte[4096];
					int bytesIn = 0;
					while ( ((byteCount < contentLength) || (waitForDisconnect)) 
							&& ((bytesIn = in.read(buf)) >= 0) )
					{
						out.write(buf, 0, bytesIn);// MK: Writing data to Output Stream
						byteCount += bytesIn;
					}
				}  catch (Exception e)  {
					String errMsg = "Error getting HTTP body: " + e;
					if (debugLevel > 0)
						debugOut.println(errMsg);
					//bs.write(errMsg.getBytes(), 0, errMsg.length());
				}
			}
		}  catch (Exception e)  {
			if (debugLevel > 0)
				debugOut.println("Error getting HTTP data: " + e);
		}
		
		//flush the OutputStream and return
		try  {
			out.flush();
			}  catch (Exception e)  {}
		return (header.length() + byteCount);
	}
	
	/***
	 * 
	 * @param in
	 * @return
	 */
	private String readLine (InputStream in)
	{
		// reads a line of text from an InputStream
		StringBuffer data = new StringBuffer("");
		int c;
		
		try
		{
			// if we have nothing to read, just return null
			in.mark(1);
			if (in.read() == -1)
				return null;
			else
				in.reset();
			
			while ((c = in.read()) >= 0)
			{
				// check for an end-of-line character
				if ((c == 0) || (c == 10) || (c == 13))
					break;
				else
					data.append((char)c);
			}
		
			// deal with the case where the end-of-line terminator is \r\n
			if (c == 13)
			{
				in.mark(1);
				if (in.read() != 10)
					in.reset();
			}
		}  catch (Exception e)  {
			if (debugLevel > 0)
				debugOut.println("Error getting header: " + e);
		}
		
		// and return what we have
		return data.toString();
	}

}

