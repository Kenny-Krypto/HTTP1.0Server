/* Coders 		*/
// NAME: Kenny K. Bui	| Camila Rocio Mata

//To enable full debug mode >> "java HTTP1Server.java PORT 1" <<

/* Imports 		*/
import java.net.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.util.*;
import java.io.*;

/* Main Class 	*/
public class HTTP3Server
{
	//Important variables
	//Debug Information Mode boolean:
	volatile static boolean bug = false;
	//if Server is listening
	volatile static boolean listening = true;
	//Server port number
	static int portNum = 0;
	//Server IP
	static String serverIP = null;
	//Max # of threads active
	final static int maxThreads = 50;
	//# of threads active
	final static int minThreads = 4;
	//# of threads active
	volatile static int numThreads = 0;
	//# of working threads active
	volatile static int workingThreads = 0;
	//Task Load
	volatile static int load = 0;
	//Socket Queue
	volatile static List<Socket> socketQueue = new ArrayList<Socket>();
	//Threads list
	volatile static List<Thread> threadList = new ArrayList<Thread>();
	//Cookie list
	//volatile static List<Strings> cookieList = new ArrayList<Strings>();
	//timeout set to 5 seconds in milliseconds
	final static int timeout = 5000;
	//report ThreadManager idle status (report idle if waiting for a job)
	final static int reportTimeout = 500; // 10 seconds ()
	//Based on ThreadManager waiting time
	final static int serverIdleLimit = 3; // 60 seconds. Set to -1 to never end automatically -> reduced to 30 seconds
	//Thread sequence naming
	volatile static int threadNumber = 0; // thread sequence number
	//clientNumber sequence naming
	volatile static int clientNumber = 1; // Client sequence number
	//Date format
	final static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd LLL YYYY HH:mm:ss zzz").withZone(ZoneId.of("GMT"));
	//Locks
	static Object loadLock = new Object();
	static Object socketLock = new Object();
	static Object cookieLock = new Object();


	/* Thread Manager Class */
	public static class ThreadManager extends Thread
	{//Deletes dead threads // Creates Threads
		private int waiting = 0;
		private int timetoExit = 0;
		public void run()
		{
			while(listening || numThreads > 0) { // keeps running until server stops and no other threads are alive
                //Waiting
				try {
					Thread.sleep(20); //sleep for 0.020 seconds
					waiting++; //time passed while doing nothing
					if(waiting == reportTimeout)
					{
						System.out.print("ThreadManager: number of Worker Threads = " + numThreads + "\n");
                        if(serverIdleLimit > -1 && workingThreads < 1)
                        {
                            timetoExit++;
                            System.out.print("ThreadManager: no active threads detected automatic shutdown in "+ (serverIdleLimit*10 - timetoExit*10) +" seconds\n");
                        }
                        else System.out.print("ThreadManager: no active threads detected\n");
						waiting = 0;
					}
					if(timetoExit == serverIdleLimit)
					{//if idle too long and if serverIdleLmit > 0, turn off server.
						System.out.print("ThreadManager: Server shutdown due to: no active threads\n");
						listening = false; //Exit server
						break;
					}
				} catch (InterruptedException e) {
		            System.err.print("ThreadManager: Could not sleep: " + Thread.currentThread().getName() + "\n");
		        }
                //End Waiting
                synchronized(loadLock)
                {
                    if(load > workingThreads)
                    {// if load is higher then amount of working threads
                        if(workingThreads == numThreads && workingThreads != maxThreads-1)
                        {// if worker threads are equal to number of threads make a new one
                            Thread newClient = new ThreadResponder(false);
                            newClient.start();
                            threadList.add(newClient);
                            numThreads++;
							waiting = 0; //something happened
							timetoExit = 0; //something happened
                        }
                    }
                }
                //Clean Up Threads
				if(numThreads > 0)
				{

	                synchronized(loadLock)
	                {
						for(int i = threadList.size()-1; i>=0; i--) //start at right of list and move left
						{
							if(threadList.get(i).isAlive())
							{
								//if alive let it keep running

								continue;
							}
							else //Clean up dead threads
							{
									try
									{
										if(bug) System.out.print("ThreadManager: joining " + threadList.get(i).getName() + "\n");
										threadList.get(i).join(); //make sure things are cleaned up
										waiting = 0; //something happened
										timetoExit = 0; //something happened
										threadList.remove(i); // remove from threadList since it's done
									} catch (InterruptedException e)
									{
							            System.err.print("ThreadManager: Could not catch: " + threadList.get(i).getName() + "\n");
							        } catch (NullPointerException e)
									{
							            System.err.print("ThreadManager: GOT NULL POINTER: " + threadList.get(i).getName() + "\n");

									}
							}
						}
						if(threadList.size() > numThreads)
							if(bug) System.out.print("ThreadManager: activeThread numThread mismatch WARNING " + threadList.size() + " VS " + numThreads + "\n");

					}
                }
                //END OF CLEAN UP THREADS
			}
		}
	}
	//END OF THREAD MGR

	/* HTTP Protocol Class */
	public static class HTTP1Protocol
	{
		//Constant Data
		private final String version = "HTTP/1.0";
		//private String date = "";
		//private final String serverVer = "RUPersonal/1.0";
		//private final String MIMEVer = "1.0";
		private final int availableCommands = 7;
		private final int availableResponses = 13;
		private final int availableMIME = 8;
		private final String[] commands = {"GET",
				"POST",
				"HEAD",
				"PUT",
				"DELETE",
				"LINK",
				"UNLINK",
				""};
		private final String[] statusResponse = {"200 OK",
                "204 No Content",
				"304 Not Modified",
				"400 Bad Request",
				"403 Forbidden",
				"404 Not Found",
                "405 Method Not Allowed",
				"408 Request Timeout",
                "411 Length Required",
				"500 Internal Server Error",
				"501 Not Implemented",
				"503 Service Unavailable",
				"505 HTTP Version Not Supported"};
		private final String[] supportedMIME = {"text/html",
				"text/plain",
				"image/gif",
				"image/jpeg",
				"image/png",
				"application/pdf",
				"application/x-gzip",
				"application/zip",};
		//END Constant Data
		//Code connection just in case we get more error codes or it changes
		public String codeResponse(String code)
		{//Connect status code with response
			for(int i = 0; i < availableResponses; i++)
			{
				if(statusResponse[i].contains(code)) return statusResponse[i];
			}
			return codeResponse("500"); //Internal server error
		}
		//Persistence check
		public boolean isPersistance(boolean mode)
		{
			return false; //HTTP1.0
			//return mode; //HTTP+
		}
		//MIME String builder
		public String MIMECoder(String input)
		{
			if(input == null) return "application/octet-stream";
			for(int i = 0; i < availableMIME; i++)
				if(supportedMIME[i].equals(input)) return supportedMIME[i];
			return "application/octet-stream";
		}
		//Start Function input
		public int parsingInput(ArrayList<String> input, boolean mode, PrintWriter outBound, Socket clientSocket, BufferedReader inBound)
		{
			//mode = false : client mode, mode = true : server mode
			//Return string:
			if(mode)
			{//Server response to Server input
				outBound.print(version + " " + codeResponse(input.get(0)) + "\r\n");
				return 0; //success
			} else
			{//Server response to client input
                //Trim beginning spaces and end spaces
                String inputTemp = input.get(0).trim();
				//Number of Spaces should be 2
				int numSpaces = inputTemp.length() - inputTemp.replaceAll(" ", "").length();
				if(numSpaces != 2)
				{//Return Bad request
					outBound.print(version + " " + codeResponse("400") + "\r\n");
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " numspaces \r\n");
					return 0; //success
				}
				String[] segmentIN = inputTemp.split(" "); //split input
				if(segmentIN.length != 3)
				{//Return Bad request
					outBound.print(version + " " + codeResponse("400") + "\r\n");
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " segmentInLeng \r\n");
					return 0; //success
				}
				//Request main line split
				String commandIN = segmentIN[0];//Command Segment
				String contextIN = segmentIN[1];//Context Segment
				String versionIN = segmentIN[2];//Version Segment

				//check if it has HTTP in 3rd slot
				if(!versionIN.contains("HTTP"))
				{//Return Bad request
					outBound.print(version + " " + codeResponse("400") + "\r\n");
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " !versionIN.contains(\"HTTP\") \r\n");
					return 0; //success
				}

				//Version Check:
				if(!versionIN.contentEquals(version))
				{//Invalid version response
					outBound.print(version + " " + codeResponse("505") + "\r\n");
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("505") + " !versionIN.contentEquals(version) \r\n");
					return 0; //success
				}
				//Version Check completed

				//Command decoding:
				int exeCommand = 0;
				for(exeCommand = 0; exeCommand <= availableCommands; exeCommand++)
					if(commandIN.contains(commands[exeCommand])) break;
				//END Command decoding

				//ContextIN cleaning for file grabbing
				if(contextIN.contains("\\"))
				{
						if(bug) System.out.print(Thread.currentThread().getName() + ": " + "found: \\ \n");
						if(contextIN.indexOf("\\") == 0) contextIN = contextIN.substring(contextIN.indexOf("\\") + 1);

				}
                if(contextIN.equals("/"))
                {// if only "/" set it to root "/index.html"
                    contextIN = "/index.html";
                }
				if(contextIN.contains("/"))
				{
						if(bug) System.out.print(Thread.currentThread().getName() + ": " + "found: / \n");
						if(contextIN.indexOf("/") == 0) contextIN = contextIN.substring(contextIN.indexOf("/") + 1);

				}

				//Multiple Lines: allows us to see what kind and to decode with given information that has been sent.
                boolean existContentLeng = false; //a check if there is a Content-Length.
                boolean existContentType = false; //a check if there is a Content-Type.
                boolean existFrom = false; //a check if there is a From line.
                boolean existUserAgent = false; //a check if there is a User-Agent.
                boolean existCookie = false; //a check if there is a cookie.
                String contentLengStr = ""; //if there is a Content-Length store that string.
                String contentTypeStr = ""; //if there is a Content-Type store that string.
                String fromStr = ""; //if there is a From line store that string.
                String userAgentStr = ""; //if there is a User-Agent store that string.
                String cookieStr = ""; //if there is a Cookie Line store that string.
				boolean ifModLine = false; //a check if there is a ifmodline for commands
				ZonedDateTime ifModDate = null; //actual date of the ifmodline
				String temp2 = null;
				if(bug) System.out.print(Thread.currentThread().getName() + ": Input size: " + input.size() + "\n");

				if(input.size() > 1) // if-modified-since
				{
					for(int i = 1; i < input.size(); i++) //checks every user input
					{
						if(bug) System.out.print(Thread.currentThread().getName() + ": "+ i +" inputs: " + input.get(i) + "\n");
                        
						//Get a subString from user input.
						if(input.get(i).indexOf(" ") > 0)
						{ // gets the leftmost substring split by a space
							temp2 = (input.get(i).substring(0, input.get(i).indexOf(" ")));
						}
						if(bug) System.out.print(Thread.currentThread().getName() + ": checking:L" + temp2 + "L\n");

                        if(temp2 == null) continue; //No substring taken -> next user input
                        
                        //GET Cookie
						if( temp2.equals("Cookie:"))
                        {
	                            temp2 = "";
	                            if(existCookie)
								{ // this means there are more then one Cookie line return bad request
									outBound.print(version + " " + codeResponse("400") + "\r\n");
                                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " existCookie \r\n");
									return 0; //success
								}
                            existCookie = true; // there is a cookie line
                            cookieStr = input.get(i); //cookie string obtained
                        }
                        
                        //GET ifModDate
						if( temp2.equals("If-Modified-Since:"))
						{
							temp2 = "";
							String temp3 = (input.get(i).substring(0, input.get(i).indexOf(" ")));
							String temp4 = input.get(i).substring(input.get(i).indexOf(" ")+1);
							if(bug) System.out.print(Thread.currentThread().getName() + ": prase check1: " + temp3 + "\n");
							if(bug) System.out.print(Thread.currentThread().getName() + ": prase check2: " + temp4 + "\n");
							if(ifModLine)
							{ // this means there are more then one If-Modified-Since return bad request
								outBound.print(version + " " + codeResponse("400") + "\r\n");
                                   if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " existContentLeng \r\n");

								return 0; //success
							}
							ifModLine = true; //there is a if-modified-since
							try
							{
								// parse everything after first space to a date
								ifModDate = ZonedDateTime.parse(temp4, DateTimeFormatter.RFC_1123_DATE_TIME);
							} catch (DateTimeParseException e)
							{//if failed then don't parse
								System.err.print(Thread.currentThread().getName() + ": " + "could not Parse if-Modified=Since: " + temp4 + ": Reason: invalid \n");
								ifModLine = false; //failed so no need to check for this in other commands
							}
						}
						//END GET ifModDate

                        if(exeCommand == 1) //if this is a post command check for additional stuff
                        {
							//Check for Content-Type & Content-Length
							if(temp2.equals("Content-Length:"))
	                        {
	                            temp2 = "";
	                            if(existContentLeng)
								{ // this means there are more then one Content-Length return bad request
									outBound.print(version + " " + codeResponse("400") + "\r\n");
                                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " existContentLeng \r\n");
									return 0; //success
								}

								existContentLeng = true; //there is a Content-Length
	                            contentLengStr = input.get(i); // store the string
	                        }
	                        if(temp2.equals("Content-Type:"))
	                        {
                                temp2 = "";
	                            if(existContentType)
								{ // this means there are more then one Content-Type return bad request
									outBound.print(version + " " + codeResponse("400") + "\r\n");
                                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " existContentType \r\n");
									return 0; //success

								}
								existContentType = true; //there is a Content-Type
	                            contentTypeStr = input.get(i); // store the string
	                        }
	                        //END check for Content-Type/leng
	                        //CHECK FOR FROM, USER-AGENT
							if(temp2.equals("From:"))
	                        {
	                            temp2 = "";
	                            if(existFrom)
								{ // this means there are more then one From return bad request
									outBound.print(version + " " + codeResponse("400") + "\r\n");
                                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " existFrom \r\n");
									return 0; //success
								}
								existFrom = true; //there is a FROM line
	                            fromStr = input.get(i); // store the string
	                        }
	                        if(temp2.equals("User-Agent:"))
	                        {
                                temp2 = "";
	                            if(existUserAgent)
								{ // this means there are more then one UserAgent return bad request
									outBound.print(version + " " + codeResponse("400") + "\r\n");
                                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " existUserAgent \r\n");
									return 0; //success
								}
								existUserAgent = true; //there is a User-Agent
	                            userAgentStr = input.get(i); // store the string
	                        }
	                        //END CHECK FOR FROM, USER-AGENT
                        }

					}
				}

				if(bug && ifModDate != null) System.out.print(Thread.currentThread().getName() + ": found ifmodDate: " + ifModDate.format(formatter) + "\n");
				else if(bug) System.out.print(Thread.currentThread().getName() + ": didn't find ifmodDate\n");

				//Command Responses:
				if(exeCommand >= 7) //if command doesn't exist
				{//Return Bad request
					outBound.print(version + " " + codeResponse("400") + "\r\n");
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("400") + " exeCommand >= 7 \r\n");
					return 0; //success
				}

				else if(exeCommand > 2) //if command exist but not Implemented
				{//not Implemented response

					outBound.print(version + " " + codeResponse("501") + "\r\n");
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("501") + " exeCommand > 2 \r\n");
					return 0; //success
				}

				else if(exeCommand == 0 ||exeCommand  == 2) //GET (0) OR HEAD (2) command
				{
                    //Check/Create Cookie:
                    LocalDateTime myDateObj = LocalDateTime.now();
                    DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                    String formattedDate = myDateObj.format(myFormatObj);
                    System.out.printf("Formatted date+time %s \n",formattedDate);
  
                    //Generate new cookie
                    boolean sendNewCookie = false;
                    String encodedDateTime = "";
                    if(true)
                    {
                        sendNewCookie = true;
                        try{
                             encodedDateTime = URLEncoder.encode(formattedDate, "UTF-8");
                        System.out.printf("URL encoded date-time %s \n",encodedDateTime);
                        }
                        catch(UnsupportedEncodingException uee){
                            System.err.print(Thread.currentThread().getName() + ": " + "UnsupportedEncodingException ");
                            outBound.print(version + " " + codeResponse("500") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " encodedDateTime = EncodingException \r\n");
                            return -1; //error
                        }
                       
                    }

                    //If given a cookie
                    String variable = "";
                    String decodedDateTime = "";
                    boolean lastTimeCookie = false;
                    //decoding Cookie time when it exists
                    if(existCookie)
                    {
                        if(!(cookieStr.contains("lasttime=")))
                        {
                        }
                        else
                        {
                            if(bug) System.out.print(Thread.currentThread().getName() + ": cookie given\r\n");
                            String tempCookie = "lasttime=";
                            String tempDateTime = "";
                            try
                            {
                                //convert from URLEncoding.
                                String tempCookiePart = cookieStr.substring(cookieStr.lastIndexOf("lasttime=")+9, cookieStr.lastIndexOf("lasttime=") + 32);
                                tempDateTime = URLDecoder.decode(tempCookiePart, "UTF-8");
                                if(bug) System.out.print(Thread.currentThread().getName() + ": Decoding: \'" + tempCookiePart +"\' into \'" + tempDateTime + "\' \r\n");//bad encoding so, no lasttime cookie

                                //Check if in correct format
                                try
                                {
                                    LocalDateTime tempdate = LocalDateTime.parse(tempDateTime,myFormatObj);
                                    tempCookie = tempCookie + tempDateTime; // correct format: "lasttime=YYYY-MM-DD HH:MM:SS"
                                    lastTimeCookie = true;
                                }
                                catch( DateTimeParseException dt)
                                {
                                    lastTimeCookie = false; //bad formmating so, no lasttime cookie   
                                    if(bug) System.out.print(Thread.currentThread().getName() + ": Bad Formmating \r\n");//bad encoding so, no lasttime cookie

                                }
                            } catch (UnsupportedEncodingException uee) 
                            {
                                lastTimeCookie = false;
                                if(bug) System.out.print(Thread.currentThread().getName() + ": Bad Encoding \r\n");//bad encoding so, no lasttime cookie
                            }
                            
                            //scan cookieList for valid cookie and poping from the cookieList
                            /*
                            synchronized(cookieLock)
                            {
                                for(i=0;i<cookieList.size();i++)
                                {
                                    if(cookieList.get(i).equals(tempCookie))
                                    {
                                        if(bug) System.out.print(Thread.currentThread().getName() + ": cookie Found in cookieList\r\n");
                                        validCookie = true;
                                        cookieList.remove(i);
                                        break;
                                    } 
                                }
                            } */  
                            //Cookie Found ! Create response payload - index_seen.html
                            if(lastTimeCookie == true)
                            {
                                if(bug) System.out.print(Thread.currentThread().getName() + ": cookie: index.html to index_seen.html\r\n");
                                if(contextIN.equals("index.html"))
                                    contextIN = "index_seen.html";
                                variable = "lasttime=";
                                decodedDateTime = tempDateTime;   
                            }
                            System.out.printf("URL decoded date-time %s \n",decodedDateTime);
                        }
                    }
                    //
                    
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "Entered GET\n");
					//Set up path to file
					Path location = null;
					try
					{
						location = Paths.get(contextIN);
					} catch (InvalidPathException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "InvalidPathException ");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:Paths.get(contextIN); \r\n");
						return -1; //error
					}
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "Found file path\n");
					//create a file
					File file = null;
					try {
						file = new File(contextIN);
					} catch (NullPointerException e) {
						//returns not found if it doesn't exists
						outBound.print(version + " " + codeResponse("404") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("404") + " GET:file = new File(contextIN); \r\n");
						return 0; //worked
					}
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "setup file\n");
					//check if file does exist

					try
					{
						if(!file.exists())
						{ //check existence
							//returns not found if it doesn't exists
							System.out.print(Thread.currentThread().getName() + ": didn't find " + file.getCanonicalPath() + "\n");
							outBound.print(version + " " + codeResponse("404") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("404") + " GET:!file.exists() \r\n");

							return 0; //worked
						}
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException File.exists\n");
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.exists() SecurityException \r\n");
						return 0; //worked
					} catch (IOException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "IOException \n");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:!file.exists() IOException \r\n");

						return -1; //error
					}
					if(bug)
					try { System.out.print(Thread.currentThread().getName() + ": found " + file.getCanonicalPath() + "\n"); }
					catch (IOException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "IOException \n");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:file.getCanonicalPath() \r\n");
						return -1; //error
					}

					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "File exists\n");

					//Check if it's a directory
					try
					{
						if(file.isDirectory())
						{// forbidden
							outBound.print(version + " " + codeResponse("403") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isDirectory() \r\n");

							return 0; //worked
						}
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException File.isDirectory\n");
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isDirectory() SE \r\n");
						return 0; //worked
					}

					//check if file is readable
					try
					{
						if(!Files.isReadable(location))
						{ // forbidden
							System.err.print(Thread.currentThread().getName() + ": " + "is unreadable File.isReadable\n");
							outBound.print(version + " " + codeResponse("403") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isReadable() \r\n");
							return 0; //worked
						}
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException File.isReadable\n");
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isReadable() SE \r\n");
						return 0; //worked
					}

					/*File does exists and is readable: */
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "File is readable\n");

					//Direct file grabbing
					//error codes
					String[] errMsgs = {"Get.Files.probeContentType",
							"Get.Files.Size / Get.File.Length",
							"Get.LocalDateTime(file.lastModified())",
							"Get.files.readAllBytes"};
					int errSelect = 0;

					//Variables
					ZonedDateTime fileLastMod = null;
					long fileLength = -1;
					String fileType = MIMECoder(null);
					InputStream fileCode = null;
					try //to get file type
					{
						fileType = Files.probeContentType(location);
						errSelect++;
						fileLength = file.length();
						errSelect++;
						Date fileTime = new Date(file.lastModified());
						fileLastMod = ZonedDateTime.ofInstant(fileTime.toInstant(), ZoneId.systemDefault());
						if(ifModLine && exeCommand == 0) //if there is a ifmodline to check against
						{
							if(bug) System.out.print(Thread.currentThread().getName() + ": comparing fileLastMod: " + fileLastMod + "\n");
							if(bug) System.out.print(Thread.currentThread().getName() + ": to ifModDate: " + ifModDate + "\n");

							if(fileLastMod.compareTo(ifModDate) <= 0) //if(ifModDate.compareTo(fileLastMod) >= 0)
							{//Has Not been modified
								if(bug) System.out.print(Thread.currentThread().getName() + ": " + ifModDate + " File is not modified\n");
								outBound.print(version + " " + codeResponse("304") + "\r\n");
								outBound.print("Expires: " + ZonedDateTime.now().plusYears(1).format(formatter) + "\r\n");
                                if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("304") + " GET:compareTo(ifModDate) \r\n");

								return 0; //success
							}
						}
						errSelect++;
						fileCode = new FileInputStream(file);
					} catch (IOException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "IOException " + errMsgs[errSelect]);
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRY: "+ errMsgs[errSelect] + " IO \r\n");
						return -1; //error
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException " + errMsgs[errSelect]);
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRY: "+ errMsgs[errSelect] + " SE \r\n");
						return 0; //worked
					} catch (OutOfMemoryError e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "OutOfMemoryError " + errMsgs[errSelect]);
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRY: "+ errMsgs[errSelect] + " OOME \r\n");
						return -1; //error
					}
					//Error catching:
					if( fileLastMod == null /*|| fileSize == -1*/ || fileLength == -1 || fileCode == null)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "Data Lost in GET command \n");
						System.err.print(Thread.currentThread().getName() + ": " + fileLastMod + " " + fileLength + " " +
								fileType + "\n");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                         if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:fileLastMod == null \r\n");
						try
						{
							fileCode.close();
						} catch (IOException e)
						{// server error
							System.err.print(Thread.currentThread().getName() + ": " + "Could not close inputStream \n");
							return -1; //error
						}
						return -1; //error
					}
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + "File is Checked\n");

                    //Flie has been checked 
                    if(lastTimeCookie && contextIN.equals("index_seen.html")) 
                    {//edit index_seen.html
                        String newFile = "index_seen" + decodedDateTime.replaceAll(":","").replaceAll("-","").replaceAll(" ","") + ".html";
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + " GET:creating temp: \'"+ newFile +"\' \r\n");
                        //now edit variables in index_seenTEMP.html
                        ArrayList<String> times = new ArrayList<String>();
                        //segments decodedDateTime into separate strings containing each value
                        for(int i=0;i<decodedDateTime.length();i++)
                        {//YYYY-MM-DD HH:MM:SS
                            if(i < decodedDateTime.length())
                            {
                                if(Character.isDigit(decodedDateTime.charAt(i)))
                                {
                                    for(int j=i;j<decodedDateTime.length()+1;j++)
                                    {
                                        
                                        if(j == decodedDateTime.length()) 
                                        { 
                                            times.add(decodedDateTime.substring(i,j));
                                            i=j+1;
                                            break;
                                        }
                                        else if(!(Character.isDigit(decodedDateTime.charAt(j))))
                                        {
                                            times.add(decodedDateTime.substring(i,j));
                                            i=j;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        //Sets eachsegemented time into its own string
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + "GET: Date is segmented\n");

                        String yearStr = times.get(0);
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + "GET: years " + times.get(0) + " is Set\n");
                        String monthStr = times.get(1);
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + "GET: months " + times.get(1) + " is Set\n");
                        String dayStr = times.get(2);
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + "GET: Days " + times.get(2) + " is Set\n");
                        String hourStr = times.get(3);
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + "GET: hours " + times.get(3) + " is Set\n");
                        String minStr = times.get(4);
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + "GET: minutes " + times.get(4) + " is Set\n");
                        String secStr = times.get(5);
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + "GET: seconds " + times.get(5) + " is Set\n");
                        
                        
                         //now edit variables in index_seenTEMP.html
                        BufferedReader inputStreamMainFile  = new BufferedReader(new InputStreamReader(fileCode));
                        File dest = new File(newFile); 
                        try 
                        {
                            //deletes file if it already exists with same file name
                            //create new file to store last visited info
                            dest.delete();
                            dest.createNewFile();
                        } catch (IOException io) 
                        {
                            System.err.print(Thread.currentThread().getName() + ": " + "IOException creatNewFile ");
                            outBound.print(version + " " + codeResponse("500") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:creatNewFile; \r\n");
                            return -1; //error
                        } catch (SecurityException se) 
                        {
                            System.err.print(Thread.currentThread().getName() + ": " + "SecurityException delete ");
                            outBound.print(version + " " + codeResponse("500") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:delete; \r\n");
                            return -1; //error
                        }
					    FileWriter outputStreamTempFile  = null;
                        try 
                        {
                            outputStreamTempFile = new FileWriter(dest);
                        } catch (IOException io) 
                        {
                            System.err.print(Thread.currentThread().getName() + ": " + "IOException outputStreamTempFile ");
                            outBound.print(version + " " + codeResponse("500") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:outputStreamTempFile; \r\n");
                            return -1; //error
                        }
                        // YYYY-MM-DD HH:MM:SS file-> index_seenYYYMMDDHHMMSS.html

                        try  
                        {//replaces each instance of %SOMETHING with the correct indicated value
                            byte[] buffer = new byte[1024];
                            int length;
                            String tempFileRead;
                            while ((tempFileRead = inputStreamMainFile.readLine()) != null) {
                                //%YEAR-%MONTH-%DAY %HOUR-%MINUTE-%SECOND
                                if(tempFileRead.contains("%YEAR"))
                                {
                                	tempFileRead = tempFileRead.replace("%YEAR",yearStr);
                                }
                                if(tempFileRead.contains("%MONTH"))
                                {
                                	tempFileRead = tempFileRead.replace("%MONTH",monthStr);
                                }
                                if(tempFileRead.contains("%DAY"))
                                {
                                	tempFileRead = tempFileRead.replace("%DAY",dayStr);
                                }
                                if(tempFileRead.contains("%HOUR"))
                                {
                                	tempFileRead = tempFileRead.replace("%HOUR",hourStr);
                                }
                                if(tempFileRead.contains("%MINUTE"))
                                {
                                	tempFileRead = tempFileRead.replace("%MINUTE",minStr);
                                }
                                if(tempFileRead.contains("%SECOND"))
                                {
                                	tempFileRead = tempFileRead.replace("%SECOND",secStr);
                                }
                                outputStreamTempFile.write(tempFileRead +"\n");
                                if(bug) System.out.print(Thread.currentThread().getName() + ": " + "GET:inputted line " + tempFileRead + " \n");
                            }
                            
                            inputStreamMainFile.close();
                            outputStreamTempFile.close();
                        } catch (IOException e) 
                        {
                            try{
                                inputStreamMainFile.close();
                                outputStreamTempFile.close();
                            } catch(IOException e2) {
                            }
                            outBound.print(version + " " + codeResponse("500") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + "\r\n");
                            return -1; //error
                        }
                        
                        //load up new tempfile to send.
                        try 
                        {
                            file = new File(newFile); 
                        } catch (NullPointerException e) {
    						//returns not found if it doesn't exists
    						outBound.print(version + " " + codeResponse("404") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("404") + " GET:file = new File(newFile); \r\n");
    						return 0; //worked
    					}
    					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "setup file\n");
                        //New location
    					try
    					{
    						location = Paths.get(newFile);
    					} catch (InvalidPathException e)
    					{// server error
    						System.err.print(Thread.currentThread().getName() + ": " + "InvalidPathException ");
    						outBound.print(version + " " + codeResponse("500") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:Paths.get(newFile); \r\n");
    						return -1; //error
    					}
    					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "Found new file path\n");
    					
                        try //to get file type
						{
							fileType = Files.probeContentType(location);
							errSelect++;
							fileLength = file.length();
							errSelect++;
							Date fileTime = new Date(file.lastModified());
							fileLastMod = ZonedDateTime.ofInstant(fileTime.toInstant(), ZoneId.systemDefault());
							if(ifModLine && exeCommand == 0) //if there is a ifmodline to check against
							{
								if(bug) System.out.print(Thread.currentThread().getName() + ": comparing fileLastMod: " + fileLastMod + "\n");
								if(bug) System.out.print(Thread.currentThread().getName() + ": to ifModDate: " + ifModDate + "\n");
	
								if(fileLastMod.compareTo(ifModDate) <= 0) //if(ifModDate.compareTo(fileLastMod) >= 0)
								{//Has Not been modified
									if(bug) System.out.print(Thread.currentThread().getName() + ": " + ifModDate + " File is not modified\n");
									outBound.print(version + " " + codeResponse("304") + "\r\n");
									outBound.print("Expires: " + ZonedDateTime.now().plusYears(1).format(formatter) + "\r\n");
	                                if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("304") + " GETnewFile:compareTo(ifModDate) \r\n");
	
									return 0; //success
								}
							}
							errSelect++;
							fileCode = new FileInputStream(file);
						} catch (IOException e)
						{// server error
							System.err.print(Thread.currentThread().getName() + ": " + "IOException " + errMsgs[errSelect]);
							outBound.print(version + " " + codeResponse("500") + "\r\n");
	                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRYnewFile: "+ errMsgs[errSelect] + " IO \r\n");
							return -1; //error
						} catch (SecurityException e)
						{// forbidden
							System.err.print(Thread.currentThread().getName() + ": " + "SecurityException " + errMsgs[errSelect]);
							outBound.print(version + " " + codeResponse("403") + "\r\n");
	                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRYnewFile: "+ errMsgs[errSelect] + " SE \r\n");
							return 0; //worked
						} catch (OutOfMemoryError e)
						{// server error
							System.err.print(Thread.currentThread().getName() + ": " + "OutOfMemoryError " + errMsgs[errSelect]);
							outBound.print(version + " " + codeResponse("500") + "\r\n");
	                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRYnewFile: "+ errMsgs[errSelect] + " OOME \r\n");
							return -1; //error
						}
						//Error catching:
						if( fileLastMod == null /*|| fileSize == -1*/ || fileLength == -1 || fileCode == null)
						{// server error
							System.err.print(Thread.currentThread().getName() + ": " + "Data Lost in GET newFile command \n");
							System.err.print(Thread.currentThread().getName() + ": " + fileLastMod + " " + fileLength + " " +
									fileType + "\n");
							outBound.print(version + " " + codeResponse("500") + "\r\n");
	                         if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GETnewFile:fileLastMod == null \r\n");
							try
							{
								fileCode.close();
							} catch (IOException e)
							{// server error
								System.err.print(Thread.currentThread().getName() + ": " + "Could not close inputStream \n");
								return -1; //error
							}
							return -1; //error
						}
	                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + "newFile is Checked\n");
	                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + "Temp newFile created\n");
                    }
                        
                    
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "sending messages\n");

					//Sending variables
					String LastModStr = fileLastMod.format(formatter);
					ZonedDateTime today = ZonedDateTime.now();
					ZonedDateTime TodayPlus1Year = today.plusYears(1);
					String newDate = TodayPlus1Year.format(formatter);

					//Sending Strings:
					String basicResponse = version + " " + codeResponse("200") + "\r\n";
					//String todayDate = "Date: " + today.format(formatter) + "\r\n";
					//String serverStr = "Server: " + serverVer + "\r\n";
					//String MIMEStr = "MIME-version: " + MIMEVer + "\r\n";
					//String connection = "Connection: closed \r";
					String allowString = "Allow: " + "GET, POST, HEAD" + "\r\n";
					String contentType = "Content-Type: " + MIMECoder(fileType) + "\r\n";
					String contentLeng = "Content-Length: " + fileLength + "\r\n";
					String expiresDate = "Expires: " + newDate + "\r\n";
					String lastModified = "Last-Modified: " + LastModStr + "\r\n";
					String contentEncode = "Content-Encoding: " + "identity" + "\r\n";
                    String setCookie = "";
                    
                    if(sendNewCookie)
                    {
                        setCookie = "lasttime=" + encodedDateTime;
                        /*
                        synchronized(cookieLock)
                        {
                            cookieList.add("lasttime=" + decodedDateTime);
                        }
                        */
                        setCookie = "Set-Cookie: "+ setCookie + "\r\n";
                        System.out.print(Thread.currentThread().getName() + " sending: " + setCookie );
                    }

					//Attempt to send:
					if(bug)
					{
						System.out.print(Thread.currentThread().getName() + " sending: " + basicResponse );
						//System.out.print(Thread.currentThread().getName() + " sending: " + todayDate     );
						//System.out.print(Thread.currentThread().getName() + " sending: " + serverStr     );
						//System.out.print(Thread.currentThread().getName() + " sending: " + MIMEStr       );
						//System.out.print(Thread.currentThread().getName() + " sending: " + connection    );
						System.out.print(Thread.currentThread().getName() + " sending: " + allowString   );
						System.out.print(Thread.currentThread().getName() + " sending: " + contentType   );
						System.out.print(Thread.currentThread().getName() + " sending: " + contentLeng   );
						System.out.print(Thread.currentThread().getName() + " sending: " + expiresDate   );
						System.out.print(Thread.currentThread().getName() + " sending: " + lastModified  );
						System.out.print(Thread.currentThread().getName() + " sending: " + contentEncode );
                        
						System.out.print(Thread.currentThread().getName() + " sending: " + setCookie );
					}

					//Send messages
					outBound.flush();
					outBound.print(basicResponse);
					outBound.flush();
					/*
					outBound.print(todayDate);
					outBound.flush();
					outBound.print(ServerStr);
					outBound.flush();
					outBound.print(MIMEStr);
					outBound.flush();
					outBound.print(connection);
					outBound.flush();
					*/
					outBound.print(allowString);
					outBound.flush();
					outBound.print(contentType);
					outBound.flush();
					outBound.print(contentLeng);
					outBound.flush();
					outBound.print(expiresDate);
					outBound.flush();
					outBound.print(lastModified);
					outBound.flush();
					outBound.print(contentEncode);
					outBound.flush();
					outBound.print(setCookie);
					outBound.flush();
					outBound.print("\r\n");
					outBound.flush();

					//Byte Streaming Code:
					BufferedInputStream inputStream  = new BufferedInputStream(fileCode);
                    //Get command
					if(exeCommand == 0)
					{
						try
						{//Send data: via byte Streaming
							if(bug) System.out.print(Thread.currentThread().getName() + " opening: DATASTREAM \n");
							if(bug) System.out.print(Thread.currentThread().getName() + " sending: PAYLOAD \n");
							OutputStream outBoundData = clientSocket.getOutputStream();
							/* Multiple part Sending
							int i = 0;
							for(i = 0; i < (int)fileLength; i = i + 10000)
							{
								outBoundData.write(inputStream.readNBytes(10000));
								//outBoundData.flush();
							}
							i = i - 10000;
							outBoundData.write(inputStream.readNBytes((int)fileLength - i));
							*/
							outBoundData.write(inputStream.readAllBytes());
							outBoundData.flush();
							try
							{
								Thread.sleep(250);
							}catch (InterruptedException e) {
					            System.err.print(Thread.currentThread().getName() + ": Could not sleep \n");
					        }
							if(bug) System.out.print(Thread.currentThread().getName() + " Completed: PAYLOAD \n");
							outBoundData.close();
							if(bug) System.out.print(Thread.currentThread().getName() + " closing: DataStream \n");
						} catch (IOException  e1)
						{// server error
							try //to clean up inputFileStream
							{
								fileCode.close();
							} catch (IOException e)
							{// server error
								System.err.print(Thread.currentThread().getName() + ": " + "Could not close inputStream \n");
								outBound.print(version + " " + codeResponse("500") + "\r\n");
								return -1; //error
							}
							System.err.print(Thread.currentThread().getName() + ": " + "Could not close/write/flush OutBoundData \n");
							outBound.print(version + " " + codeResponse("500") + "\r\n");
							return -1; //error

						}
					}
					try //to clean up inputFileStream
					{
						fileCode.close();
					} catch (IOException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "Could not close inputStream \n");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
						return -1; //error
					}
					return 0; //success


				}// END OF GET & HEAD COMMAND


                //POST command
				else if(exeCommand == 1)
				{ // Content-Type: application/x-ww-from-urlencoded
                    //ContentType vaild input check
                    if(!existContentType) //not given
                    { //Internal server error?
                        outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " POST:existContentType \r\n");
						return 0;
                    }
                    if(!contentTypeStr.substring(contentTypeStr.indexOf(" ")+1).equals("application/x-www-form-urlencoded") )
                    { //500 Internal server Error
                        outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " POST:!contentTypeStr \r\n");
						return 0;
                    }

                    //ContentLeng valid input check
                    if(!existContentLeng) //not given
                    {
                        // Error 411
                        outBound.print(version + " " + codeResponse("411") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("411") + " POST:!existContentLeng \r\n");
						return 0;
                    }
                    int contentLengNum = 0;
                    try
                    {
                        contentLengNum = Integer.parseInt(contentLengStr.substring(contentLengStr.indexOf(" ")+1)); //right side of the :
                    } catch (NumberFormatException n)
                    {// non-numbers catching ""
                        outBound.print(version + " " + codeResponse("411") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("411") + " POST:non number \r\n");
						return 0;
                    }
                    if(contentLengNum<0)
                    {// if it's negative, it's invalid
                        outBound.print(version + " " + codeResponse("411") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("411") + " POST:negative number \r\n");
						return 0;
                    }


                    // check if it is a cgi file
                    int tempEndIndex = contextIN.length() - 1;
                    String givenFileType = contextIN.substring(tempEndIndex-3);
                    //if there is no cgi script, meaning not ending in .cgi return 405
                    if(!givenFileType.equals(".cgi"))
                    { // Non cgi file.
                        outBound.print(version + " " + codeResponse("405") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("405") + " POST:(!givenFileType.equals(\".cgi\"))  \r\n");
						return 0;
                    }

                    //File checking
                    //Set up path to file
					Path location = null;
					try
					{
						location = Paths.get(contextIN);
					} catch (InvalidPathException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "InvalidPathException ");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:Paths.get(contextIN); \r\n");
						return -1; //error
					}
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "Found file path\n");
					//create a file
					File file = null;
					try {
						file = new File(contextIN);
					} catch (NullPointerException e) {
						//returns not found if it doesn't exists
						outBound.print(version + " " + codeResponse("404") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("404") + " GET:file = new File(contextIN); \r\n");
						return 0; //worked
					}
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "setup file\n");
					//check if file does exist

					try
					{
						if(!file.exists())
						{ //check existence
							//returns not found if it doesn't exists
							System.out.print(Thread.currentThread().getName() + ": didn't find " + file.getCanonicalPath() + "\n");
							outBound.print(version + " " + codeResponse("404") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("404") + " GET:!file.exists() \r\n");

							return 0; //worked
						}
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException File.exists\n");
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.exists() SecurityException \r\n");
						return 0; //worked
					} catch (IOException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "IOException \n");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:!file.exists() IOException \r\n");

						return -1; //error
					}
					if(bug)
					try { System.out.print(Thread.currentThread().getName() + ": found " + file.getCanonicalPath() + "\n"); }
					catch (IOException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "IOException \n");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:file.getCanonicalPath() \r\n");
						return -1; //error
					}

					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "File exists\n");

					//Check if it's a directory
					try
					{
						if(file.isDirectory())
						{// forbidden
							outBound.print(version + " " + codeResponse("403") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isDirectory() \r\n");

							return 0; //worked
						}
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException File.isDirectory\n");
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isDirectory() SE \r\n");
						return 0; //worked
					}

                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + "is not a directory\n");

					//check if file is readable
					try
					{
						if(!Files.isReadable(location))
						{ // forbidden
							System.err.print(Thread.currentThread().getName() + ": " + "is unreadable File.isReadable\n");
							outBound.print(version + " " + codeResponse("403") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isReadable() \r\n");
							return 0; //worked
						}
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException File.isReadable\n");
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isReadable() SE \r\n");
						return 0; //worked
					}

					//check if file is executable.
					try
					{
						if(!file.canExecute())
						{ // forbidden
							System.err.print(Thread.currentThread().getName() + ": " + "is unreadable File.isReadable\n");
							outBound.print(version + " " + codeResponse("403") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isReadable() \r\n");
							return 0; //worked
						}
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException File.isReadable\n");
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:!file.isReadable() SE \r\n");
						return 0; //worked
					}

                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + "is readable&Executable\n");
                    // file is valid

					//Direct file grabbing
					//error codes
					String[] errMsgs = {"Get.Files.probeContentType",
							"Get.Files.Size / Get.File.Length",
							"Get.LocalDateTime(file.lastModified())",
							"Get.files.readAllBytes"};
					int errSelect = 0;

					//Variables
					ZonedDateTime fileLastMod = null;
					long fileLength = -1;
					String fileType = MIMECoder(null);
					InputStream fileCode = null;
					try //to get file type
					{
						fileType = Files.probeContentType(location);
						errSelect++;
						fileLength = file.length();
						errSelect++;
						Date fileTime = new Date(file.lastModified());
						fileLastMod = ZonedDateTime.ofInstant(fileTime.toInstant(), ZoneId.systemDefault());
						if(ifModLine && exeCommand == 0) //if there is a ifmodline to check against
						{
							if(bug) System.out.print(Thread.currentThread().getName() + ": comparing fileLastMod: " + fileLastMod + "\n");
							if(bug) System.out.print(Thread.currentThread().getName() + ": to ifModDate: " + ifModDate + "\n");

							if(fileLastMod.compareTo(ifModDate) <= 0) //if(ifModDate.compareTo(fileLastMod) >= 0)
							{//Has Not been modified
								if(bug) System.out.print(Thread.currentThread().getName() + ": " + ifModDate + " File is not modified\n");
								outBound.print(version + " " + codeResponse("304") + "\r\n");
								outBound.print("Expires: " + ZonedDateTime.now().plusYears(1).format(formatter) + "\r\n");
                                if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("304") + " GET:compareTo(ifModDate) \r\n");

								return 0; //success
							}
						}
						errSelect++;
						fileCode = new FileInputStream(file);
					} catch (IOException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "IOException " + errMsgs[errSelect]);
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRY: "+ errMsgs[errSelect] + " IO \r\n");
						return -1; //error
					} catch (SecurityException e)
					{// forbidden
						System.err.print(Thread.currentThread().getName() + ": " + "SecurityException " + errMsgs[errSelect]);
						outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRY: "+ errMsgs[errSelect] + " SE \r\n");
						return 0; //worked
					} catch (OutOfMemoryError e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "OutOfMemoryError " + errMsgs[errSelect]);
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " GET:TRY: "+ errMsgs[errSelect] + " OOME \r\n");
						return -1; //error
					}
					//Error catching:
					if( fileLastMod == null /*|| fileSize == -1*/ || fileLength == -1 || fileCode == null)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "Data Lost in GET command \n");
						System.err.print(Thread.currentThread().getName() + ": " + fileLastMod + " " + fileLength + " " +
								fileType + "\n");
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                         if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " GET:fileLastMod == null \r\n");
						try
						{
							fileCode.close();
						} catch (IOException e)
						{// server error
							System.err.print(Thread.currentThread().getName() + ": " + "Could not close inputStream \n");
							return -1; //error
						}
						return -1; //error
					}

                    if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process Getting payload\r\n");

                    //get payload,
                    String payloadPost = "";
                    String decode = "";


                    if(contentLengNum > 0) //if there is a payload obtain it.
                    {
                        try
                        {
                            String tempStr1 = "";
                            while((tempStr1 =  inBound.readLine()) != null) 
                            {
                                payloadPost = payloadPost + tempStr1;
                            }
                        } catch (IOException e)
                        { //500 Internal server Error
                            System.err.print(Thread.currentThread().getName() + ": " + "Could not listen on clientSocket\n");
                            outBound.print(version + " " + codeResponse("500") + "\r\n");
                            if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " POST:inBound.readLine  \r\n");
                            return -1; //error
                        }
                        if(payloadPost.length() < 1)
                            System.err.print("Some issue with payload length\n");

                        if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process payload obtained\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + payloadPost +"\r\n");

						//check that payload and content length match, if not grab the correct payloadPost
						if(contentLengNum < payloadPost.length())
						{
							payloadPost = payloadPost.substring(0,contentLengNum); //adjusts payload to only include indexs up to contentLengNum
						}
						/* else if(contentLengNum > payloadPost.length())
						{
							//contentLengNum = payloadPost.length(); //corrects contentLengNum to match payload length
						} */
						
                        //decode payload
                        // = and & can't be encoded.
                        // & = new pair
                        // !Symbol = Symbol
                        //"!     *     '     (     )     ;     :     @     $   +   ,     /     ?     #     [     ]"
                        decode = payloadPost;

                        decode = decode.replace("!!", "!");
                        decode = decode.replace("!*", "*");
                        decode = decode.replace("!'", "'");
                        decode = decode.replace("!(", "(");
                        decode = decode.replace("!)", ")");
                        decode = decode.replace("!;", ";");
                        decode = decode.replace("!:", ":");
                        decode = decode.replace("!@", "@");
                        decode = decode.replace("!$", "$");
                        decode = decode.replace("!+", "+");
                        decode = decode.replace("!,", ",");
                        decode = decode.replace("!/", "/");
                        decode = decode.replace("!?", "?");
                        decode = decode.replace("!#", "#");
                        decode = decode.replace("!]", "]");
                        decode = decode.replace("![", "[");
                        //x=! encoded becomes x=!!
                        //x=!&y=@ encoded becomes x=!!&y=!@

                        if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process payload decoded\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + decode +"\r\n");
                    }//payload obtained


                    //setup Environmental variables
                    String CONTENT_LENGTH = Integer.toString(contentLengNum);
                    String SCRIPT_NAME = "/" + contextIN;
                    String SERVER_NAME = serverIP;
                    String SERVER_PORT = Integer.toString(portNum);
                    String HTTP_FROM = "";
                    String HTTP_USER_AGENT = "";
                    //GET FILE PATH
                    /*
                    try
                    {
                        SCRIPT_NAME = file.getCanonicalPath();
                    } catch (IOException e)
					{// server error
						System.err.print(Thread.currentThread().getName() + ": " + "IOException " + errMsgs[errSelect]);
						outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + codeResponse("500") + " POST:TRY: " + " IO \r\n");
						return -1; //error
					}
                      */
                    //check that the headers FROM and USER AGENT exists and initializes them
                    if(existFrom)
                    {
                        HTTP_FROM = fromStr.substring(fromStr.indexOf(" ")+1);
                    }
                    if(existUserAgent)
                    {
                        HTTP_USER_AGENT = userAgentStr.substring(userAgentStr.indexOf(" ")+1);
                    }

                    if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process setup starting1\r\n");

                    //Eviromental variables check
                    if(bug)
                    {
                        if(bug) System.out.print(Thread.currentThread().getName() + ": CONTENT_LENGTH = " + CONTENT_LENGTH + " Len:" + CONTENT_LENGTH.length() + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": SCRIPT_NAME = " + SCRIPT_NAME + " Len:" + SCRIPT_NAME.length() + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": SERVER_NAME = " + SERVER_NAME + " Len:" + SERVER_NAME.length() + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": SERVER_PORT = " + SERVER_PORT + " Len:" + SERVER_PORT.length() + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": HTTP_FROM = " + HTTP_FROM + " Len:" + HTTP_FROM.length() + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": HTTP_USER_AGENT = " + HTTP_USER_AGENT + " Len:" + HTTP_USER_AGENT.length() + "\r\n");
                    }

                    //setup of cgi process
                    //Runtime rt = Runtime.getRuntime();
                    String[] commands = ("."+SCRIPT_NAME+"&"+decode).split("&"); // CAN"T BE NULL passing: {pathname,x=3,y=$,x=3}

                    //String[] evnp = {CONTENT_LENGTH, SCRIPT_NAME, SERVER_NAME, SERVER_PORT, HTTP_FROM, HTTP_USER_AGENT}; //Environmental variables
                    ProcessBuilder cgiprocb = new ProcessBuilder(commands); //Process builder
                    Map<String,String> evnp = null; //Enviroment variables
                    Process cgiproc = null; //Process
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process setup starting2\r\n");
                    try
                    {
                    	evnp = cgiprocb.environment(); //Set up enviromental variables:
                    	evnp.put("CONTENT_LENGTH",CONTENT_LENGTH);
                        evnp.put("SCRIPT_NAME",SCRIPT_NAME);
                        evnp.put("SERVER_NAME",SERVER_NAME);
                        evnp.put("SERVER_PORT",SERVER_PORT);
                        evnp.put("HTTP_FROM",HTTP_FROM);
                        evnp.put("HTTP_USER_AGENT",HTTP_USER_AGENT);
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process setup starting3\r\n");
                        //cgiprocb.command(SCRIPT_NAME); // add the comamnds in.
                        cgiproc = cgiprocb.start(); //Start process
                    } catch (SecurityException s) // no permissions
                    {
                        outBound.print(version + " " + codeResponse("403") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("403") + " POST:exec(commands,evnp);  \r\n");
                        return 0;
                    } catch (IOException e) // internal server error
                    {
                        outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " POST:exec(commands,evnp);  \r\n");
                        return -1;//error
                    }
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process started\r\n");
                    //closing input
                    try
                    {
                        cgiproc.getOutputStream().flush();
                        cgiproc.getOutputStream().close();
                    } catch (IOException e)
                    {
                        outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " POST:readLine()  \r\n");
                        return -1; //error
                    	// internal server error
                    }
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process closed input\r\n");

                    //Reading output
                    BufferedReader stdInput = new BufferedReader(new
                         InputStreamReader(cgiproc.getInputStream()));

                    BufferedReader stdError = new BufferedReader(new
                         InputStreamReader(cgiproc.getErrorStream()));

                    if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process reader started\r\n");

                    // Read the output from the command
                    String procOutput = "";
                    String procErrorOutput = "";
                    try
                    {
	                    if(bug) System.out.println("Here is the standard output of the command:\n");
	                    String s = null;
	                    while ((s = stdInput.readLine()) != null) {
	                        if(bug) System.out.println(s);
                            procOutput = procOutput + s + "\n";
	                    }

	                    // Read any errors from the attempted command
	                    if(bug) System.out.println("Here is the standard error of the command (if any):\n");
	                    while ((s = stdError.readLine()) != null) {
	                        if(bug) System.out.println(s);
                            procErrorOutput = procErrorOutput + s + "\n";
	                    }

                        try
                        { //Close Streams
                            stdInput.close();
                            stdError.close();
                        } catch (IOException e)
                        {
                            //do nothing should be closed now.
                        }


                    } catch (IOException e)
                    {
                        outBound.print(version + " " + codeResponse("500") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("500") + " POST:readLine()  \r\n");
                        return -1; //error
                    	// internal server error
                    }
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " +"cgi process output obtained\r\n");
                    //if(bug) System.out.print(Thread.currentThread().getName() + ": " + procOutput + "\r\n");

                    //if output from proc is empty return 204
                    if(procOutput == null || procOutput == ""  )
                    {
                        outBound.print(version + " " + codeResponse("204") + "\r\n");
                        if(bug) System.out.print(Thread.currentThread().getName() + ": " + version + " " + codeResponse("204") + " POST:emptyOutput  \r\n");
                        return 0;
                        // Empty/No process response
                    }


					//Sending Strings:
                    if(bug) System.out.print(Thread.currentThread().getName() + ": " + "sending messages\n");

					//Sending variables
					String LastModStr = fileLastMod.format(formatter);
					ZonedDateTime today = ZonedDateTime.now();
					ZonedDateTime TodayPlus1Year = today.plusYears(1);
					String newDate = TodayPlus1Year.format(formatter);

					//Sending Strings:
					String basicResponse = version + " " + codeResponse("200") + "\r\n";
					//String todayDate = "Date: " + today.format(formatter) + "\r\n";
					//String serverStr = "Server: " + serverVer + "\r\n";
					//String MIMEStr = "MIME-version: " + MIMEVer + "\r\n";
					//String connection = "Connection: closed \r";
					String allowString = "Allow: " + "GET, POST, HEAD" + "\r\n";
					String contentType = "Content-Type: " + "text/html" + "\r\n";
					String contentLeng = "Content-Length: " + procOutput.length() + "\r\n";
					String expiresDate = "Expires: " + newDate + "\r\n";
					String lastModified = "Last-Modified: " + LastModStr + "\r\n";
					String contentEncode = "Content-Encoding: " + "identity" + "\r\n";

					//Attempt to send:
					if(bug)
					{
						System.out.print(Thread.currentThread().getName() + " sending: " + basicResponse );
						//System.out.print(Thread.currentThread().getName() + " sending: " + todayDate     );
						//System.out.print(Thread.currentThread().getName() + " sending: " + serverStr     );
						//System.out.print(Thread.currentThread().getName() + " sending: " + MIMEStr       );
						//System.out.print(Thread.currentThread().getName() + " sending: " + connection    );
						System.out.print(Thread.currentThread().getName() + " sending: " + allowString   );
						System.out.print(Thread.currentThread().getName() + " sending: " + contentType   );
						System.out.print(Thread.currentThread().getName() + " sending: " + contentLeng   );
						System.out.print(Thread.currentThread().getName() + " sending: " + expiresDate   );
						System.out.print(Thread.currentThread().getName() + " sending: " + lastModified  );
						System.out.print(Thread.currentThread().getName() + " sending: " + contentEncode );
					}

					//Send messages
					outBound.flush();
					outBound.print(basicResponse);
					outBound.flush();
					/*
					outBound.print(todayDate);
					outBound.flush();
					outBound.print(ServerStr);
					outBound.flush();
					outBound.print(MIMEStr);
					outBound.flush();
					outBound.print(connection);
					outBound.flush();
					*/
					outBound.print(allowString);
					outBound.flush();
					outBound.print(contentType);
					outBound.flush();
					outBound.print(contentLeng);
					outBound.flush();
					outBound.print(expiresDate);
					outBound.flush();
					outBound.print(lastModified);
					outBound.flush();
					outBound.print(contentEncode);
					outBound.flush();
					outBound.print("\r\n");
					outBound.flush();

                    //Sending payload
				    if(bug) System.out.print(Thread.currentThread().getName() + " sending Payload: \"" + procOutput + "\"\r\n" );
                    outBound.flush();
					outBound.print(procOutput + "\r\n");
                    outBound.flush();

					return 0; //success


				}// END OF POST COMMAND

				//End Command Responses


			}

			//End of output
			//output = version + " " + codeResponse("200") + "\n" + output;
			return -2;
		}
		//End Function input
	}

	/* Thread Response Class */
	public static class ThreadResponder extends Thread
	{
		//Variables
		public volatile static Socket newClientSocket = null; // new socket
		public Socket clientSocket = null; // current socket
		public boolean persistence = false;
        //Start up HTTP response protocol
	    HTTP1Protocol HTTP = new HTTP1Protocol();
		//private boolean persistent = false;
		//END Variable Data
		//Construct Thread socket target
		public ThreadResponder(boolean persist)
		{
			super("ThreadResponder");
			this.persistence = HTTP.isPersistance(persist);
			this.setName("Thread " + ++threadNumber);
		}

		public void run()
		{
	        //Start!
			System.out.print(Thread.currentThread().getName() + " started!\n");
	        //Error catching variables
			int temp1 = 0;
			final String[] errorString1 = {"null","Outbound close", "Inbound close","client close"};
			//set up reader/writer variables
	        PrintWriter outBound = null;
	        BufferedReader inBound = null;
    	    String inputLine;
	        ArrayList<String> clientMessage = new ArrayList<String>();
	        boolean timeToExit = false; //tells the program to exit if it's true
	        while(true)
	        {
                //
                int fails=0;
                while(true)
                {
                    synchronized(loadLock)
                    {//Now working
	                    synchronized(socketLock)
	                    {
	                    	//if(bug) System.out.print(Thread.currentThread().getName() + ": SocketQueue Size: " + socketQueue.size() + "\n");
	                        if(socketQueue.size()>0)
	                        {  //if sockets are queued take pop a socket and do the task
	                        	if(bug) System.out.print(Thread.currentThread().getName() + ": popping Socket\n");
	                        	System.out.print(Thread.currentThread().getName() + ": got client "+ clientNumber++ +".\n");
	                            clientSocket = socketQueue.get(0);
	                            workingThreads++;
	                            socketQueue.remove(0); //pop
	                            fails = 0;
	                            break;
	                        }
	                    }//socket lock end
                    }
                    fails++;
					try
					{
						Thread.sleep(10);//wait a 0.01 second
					}catch (InterruptedException e) {
			            System.err.print(Thread.currentThread().getName() + ": Could not sleep \n");
			        }
					if(fails%250 == 0)
                    	System.out.print(Thread.currentThread().getName() + ": not active.\n");
					synchronized(loadLock)
					{
	                    if(fails==100 && numThreads>=minThreads) // 500 -> 100
	                    { // 5 seconds till shutdown when idleing --> changed to 1s
	                    	System.out.print(Thread.currentThread().getName() + ": Ending\n");
	                        numThreads--;
	                        timeToExit = true;
	                        return;
	                    }
					}
					if(listening == false)
					{
                    	System.out.print(Thread.currentThread().getName() + ": Ending\n");
                    	return;
					}
                }
                if(timeToExit) return;

                //
	        	int result = 0; // checking if any errors
		        try
		        {
		        	//Set timeout for socket
		        	clientSocket.setSoTimeout(timeout);
		            // Connect the I/O connections with client
		            outBound =
		                new PrintWriter(clientSocket.getOutputStream(), true); //Server response to Client connection
		            inBound = new BufferedReader(
		                new InputStreamReader(clientSocket.getInputStream())); //Client response to Server connection

		            // Initiate conversation with client
		            /* Don't greet
		            if (bug) System.out.print(Thread.currentThread().getName() + ": Greeting client\n");
		            clientMessage.clear();
		            clientMessage.add("200");
		            result = HTTP.parsingInput(clientMessage, true, outBound); // return 200 OK
		            */
		            clientMessage.clear();
		            while(true) //for persistence connection.
		            {
			            if (bug) System.out.print(Thread.currentThread().getName() + ": listening to client\n");
		            	while ( (inputLine = inBound.readLine()) != null )
		            	{//Gathers all messages put into arrayList
				            if (bug) System.out.print(Thread.currentThread().getName() + ": got: '" + inputLine + "'\n");
		            		clientMessage.add(inputLine);
		            		if(inputLine.isEmpty()) break;
		            	}
			            if (bug) System.out.print(Thread.currentThread().getName() + ": Responding to client\n");
		            	result = HTTP.parsingInput(clientMessage, false, outBound, clientSocket, inBound); //return response
	    	            clientMessage.clear();
		            	if(!persistence) break; //if persistence is false
		            	if(result == -1)
		            	{ // Break if error gotten
		            		break;
		            	}
		            }
		        } catch (SocketTimeoutException exception)
		        {
		            // Output expected SocketTimeoutExceptions.
		            if (bug) System.out.print(Thread.currentThread().getName() + ": SockTimeOutExp send timeout\n");
		            clientMessage.clear();
		            clientMessage.add("408");
	        	    result = HTTP.parsingInput(clientMessage, true, outBound, clientSocket, null); // return 408 Timeout
		            clientMessage.clear();
		        } catch (NullPointerException e)
		        {
		        	// Output expected NullPointerExp.
		            if (bug) System.out.print(Thread.currentThread().getName() + ": NullPointExp at threadResponderlvl\n");
		            clientMessage.clear();
		            clientMessage.add("500");
	        	    result = HTTP.parsingInput(clientMessage, true, outBound, clientSocket, null); // return 500 Server Error
		            clientMessage.clear();
		        }catch (IOException e)
		        {
		            System.err.print(Thread.currentThread().getName() + ": " + "Could not listen on clientSocket\n");
		            System.exit(-1);
		        }
		        //Cleanup
				try {
					outBound.flush(); //flush
					try
					{
						Thread.sleep(250);//wait a quarter second
					}catch (InterruptedException e) {
			            System.err.print(Thread.currentThread().getName() + ": Could not sleep \n");
			        }
					temp1++;
					outBound.close();
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "closed outBound\n");
					temp1++;
					inBound.close();
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "closed inBound\n");
					temp1++;
					clientSocket.close();
					if(bug) System.out.print(Thread.currentThread().getName() + ": " + "closed ClientSocket\n");
				} catch (IOException e) {
		            System.err.print("Could not clean up thread: " + Thread.currentThread().getName()+ errorString1[temp1] + "\n");
		            System.exit(-1);
		        }
                //Task has ended
                synchronized(loadLock)
                {
                    load--;
                    workingThreads--;
                }

			}//Main While END
		}
	}

	/* Main Server Process Class */
	public static void main(String[] args) throws IOException
	{
		//Set up temp
		ArrayList<String> temp1 = new ArrayList<String>(); // for asking HTTP protocol for messages
		//Get port number for server:
		if(args.length == 0 )
		{
			System.out.print("Main: No port number inputed detected\n");
			System.exit(-1);
		}
		//Set port number:
		portNum = Integer.parseInt(args[0]);
		//Set debugging mode
		if(args.length >= 2 )
		{
			System.out.print("Main: Debug inputed detected\n");
			bug = true;
		}
        //Start up HTTP response protocol
	    HTTP1Protocol HTTPMain = new HTTP1Protocol();
	    //Start up Thread Manager
	    Thread clientManager = new ThreadManager();
	    clientManager.start();
		//Set up completed
		System.out.print("Main: Program starting\n");
		//setup socket variables
		Socket clientSocket = null;
		ServerSocket serverSocket = null;
		//Start server
		try
		{
			//create server:
			serverSocket = new ServerSocket(portNum);
			System.out.print("Main: Server starting\n");
			serverSocket.setSoTimeout(timeout);
            //get ip address for SERVER_NAME
            InetAddress ip;
            try
            {
                ip = InetAddress.getLocalHost();
                serverIP = ip.getHostAddress();
            }catch (UnknownHostException e) {

                e.printStackTrace();
            }
			while(listening)
			{
				try {
					clientSocket = serverSocket.accept();
				} catch (SocketTimeoutException e)
				{//Timeout check if still listening
					continue;
				} catch (IOException e)
				{//error with client accept -> break and stop accepting
		            System.err.print("Main: Could not accept client. IOException\n");
		            break;
				} catch (SecurityException e)
				{//error with client accept -> break and stop accepting
		            System.err.print("Main: Could not accept client. SecurityException\n");
		            break;
				}
                //
                boolean check1 = false;
                    synchronized(loadLock){
                        if(load<maxThreads-1){
                            load++;
                            synchronized(socketLock){
                                socketQueue.add(clientSocket);
                            }
                            check1 = true;
                        }
                    }


                //
                if(!check1)
				{// 503 Service Unavailable Response
				    PrintWriter outBound = null;
					try
					{
					    outBound = new PrintWriter(clientSocket.getOutputStream(), true);
					    // report to client service unavailable
						temp1.add("503");
			            HTTPMain.parsingInput(temp1, true, outBound, clientSocket, null); // return 503 Service Unavailable
						clientSocket.close(); //Close Socket
						temp1.clear();
						try
						{//Wait for some space
							Thread.sleep(250);
						}catch (InterruptedException e) {
				            System.err.print("Main: Could not sleep \n");
				        }

					} catch (IOException e) {
			            System.err.print("Main: Could not deal with sending 503\n");
					    // report to client internal error
						temp1.add("500");
			            HTTPMain.parsingInput(temp1, true, outBound, clientSocket, null); // return 500 Internal server error
						clientSocket.close(); //Close Socket
						temp1.clear();
			        }

				} //END 503 Response
                check1 = false;
			}
		} catch (IOException e) {
            System.err.print("Could not listen on port " + portNum + "\n");
            System.exit(-1);
        }
		//End of server Camila
		try {
			if(bug) System.out.print("Main: waiting for ThreadManager to stop\n");
			clientManager.join();
			if(bug) System.out.print("Main: joined ThreadManager\n");
		} catch (InterruptedException e) {
            System.err.print("Main: Could not join ThreadManager\n");
            System.exit(-1);
        }
		try { // clean up serverSocket
			if(bug) System.out.print("Main: closing serverSocket\n");
			serverSocket.close();
			if(bug) System.out.print("Main: serverSocket closed\n");
		}catch (IOException e)
		{//error with closing serverSocket
            System.err.print("Main: oof serversocket didn't close properly\n");
            System.exit(-1);
		}
		System.out.print("Main: Program Ending \n");
        System.exit(0);
	}
}
