/*
 * Created on Jan 6, 2016
 * Created by Paul Gardner
 * 
 * Copyright 2016 Azureus Software, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or 
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package org.parg.azureus.plugins.webtorrent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.websocket.PongMessage;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;

import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import com.biglybt.core.security.SESecurityManager;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.ByteArrayHashMap;
import com.biglybt.core.util.ByteEncodedKeyHashMap;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SystemTime;
import com.biglybt.pifimpl.local.clientid.ClientIDManagerImpl;
import org.parg.azureus.plugins.webtorrent.WebRTCProvider.Answer;
import org.parg.azureus.plugins.webtorrent.WebRTCProvider.Offer;

import com.biglybt.util.JSONUtils;

public class 
TrackerProxy 
	implements LocalWebServer.Listener
{
	private static final boolean TRACE = false;
    private final java.util.concurrent.atomic.AtomicLong offerBatches = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong answersReceived = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong offersReceived = new java.util.concurrent.atomic.AtomicLong();

    private void diagnostic(String event, String trackerUrl, String detail) {
        TrackerDiagnostics.report(plugin, "proxy", event, trackerUrl, detail);
    }
	
	private final WebTorrentPlugin		plugin;
	private final Listener				listener;
	
	private Map<String,ClientSession>	client_sessions = new HashMap<String, ClientSession>();
	private final TrackerConnectionGate connection_gate = new TrackerConnectionGate();
	private final TrackerAnnounceRegistry announce_registry = new TrackerAnnounceRegistry();
	private static final long PING_INTERVAL_MS = 60_000;
	private static final long PONG_TIMEOUT_MS = 30_000;
    private final long pingIntervalMs;
    private final long pongTimeoutMs;
    private final long writerTimeoutMs;
    private final int minimumReadTimeoutMs;
	private final ScheduledExecutorService ping_executor = Executors.newScheduledThreadPool(2, task -> {
		Thread thread = new Thread(task, "azwebtorrent-tracker-ping");
		thread.setDaemon(true);
		return thread;
	});

	private ByteArrayHashMap<byte[]>	hash_to_peer_id_map 		= new ByteArrayHashMap<>();
	private ByteArrayHashMap<byte[]>	hash_to_scrape_peer_id_map 	= new ByteArrayHashMap<>();
	
	protected
	TrackerProxy(
		WebTorrentPlugin	_plugin,
		Listener			_listener )
    {
        this(_plugin, _listener, PING_INTERVAL_MS, PONG_TIMEOUT_MS, 30_000, 30_000);
    }

    // Package-private timing control for deterministic local integration tests.
    TrackerProxy(WebTorrentPlugin _plugin, Listener _listener,
            long pingIntervalMs, long pongTimeoutMs,
            long writerTimeoutMs, int minimumReadTimeoutMs) {
        plugin = _plugin;
        listener = _listener;
        this.pingIntervalMs = pingIntervalMs;
        this.pongTimeoutMs = pongTimeoutMs;
        this.writerTimeoutMs = writerTimeoutMs;
        this.minimumReadTimeoutMs = minimumReadTimeoutMs;
    }

	@Override
	public void 
	handleRequest(
		String 			original_url, 
		OutputStream 	os )
			
		throws Exception 
	{
        // Sanitized by TrackerDiagnostics; never log the raw URL or query.
        final String trackerLogUrl = original_url;
        try{
			int	q_pos = original_url.indexOf( '?' );
			
			final String	ws_url = original_url.substring( 0,  q_pos );
			
			String[] bits = original_url.substring( q_pos+1 ).split( "&" );
			
			int		numwant		= 0;
			long	uploaded	= 0;
			long	downloaded	= 0;
			long	left		= 0;
			
			String	event		= null;
			byte[]	info_hash	= null;
			byte[]	peer_id		= null;
			
			for ( String bit: bits ){
				
				String[]	temp = bit.split( "=" );
				
				String	lhs = temp[0];
				String	rhs	= temp[1];
				
				if ( lhs.equals( "numwant" )){
					
					numwant = Integer.parseInt( rhs );
					
				}else if ( lhs.equals( "uploaded" )){
					
					uploaded = Long.parseLong( rhs );
					
				}else if ( lhs.equals( "downloaded" )){
					
					downloaded = Long.parseLong( rhs );
					
				}else if ( lhs.equals( "left" )){
					
					left = Long.parseLong( rhs );
					
				}else if ( lhs.equals( "event" )){
					
					event	= rhs;
					
				}else if ( lhs.equals( "info_hash" )){
	
					info_hash = URLDecoder.decode( rhs, "ISO-8859-1" ).getBytes( "ISO-8859-1" );
					
				}else if ( lhs.equals( "peer_id" )){
	
					peer_id = URLDecoder.decode( rhs, "ISO-8859-1" ).getBytes( "ISO-8859-1" );
				}		
			}
			
			if ( info_hash == null ){
				
				throw( new Exception( "hash missing" ));
			}
			
		  	String	read_timeout_str 	= System.getProperty("sun.net.client.defaultReadTimeout"); 

		  	int	read_timeout	= Integer.parseInt( read_timeout_str );
		  	
		  	read_timeout = Math.max( read_timeout, minimumReadTimeoutMs );
		  	
			boolean	is_stop = event != null && event.equals( "stopped" );
			
			final boolean scrape = peer_id == null;
			
			final List<Offer>	offers;
			
			if ( scrape ){
				
				offers = null;
				
				numwant	= 0;
				
				synchronized( hash_to_scrape_peer_id_map ){
					
					peer_id = hash_to_scrape_peer_id_map.get( info_hash );
					
					if ( peer_id == null ){
				
						peer_id = ClientIDManagerImpl.getSingleton().generatePeerID( info_hash, true );
						
						hash_to_scrape_peer_id_map.put( info_hash, peer_id );
					}
				}
			}else{
				
				synchronized( hash_to_peer_id_map ){
				
					hash_to_peer_id_map.put( info_hash, peer_id );
				}
				
				if ( is_stop ){
				
					offers = null;
					
				}else{
					
					long	start = SystemTime.getMonotonousTime();
					
					int	offers_to_generate = left==0?Math.min( numwant, 4 ):Math.min( numwant,  8 );
						
					final AESemaphore	sem = new AESemaphore( "" );
					
					offers = new ArrayList<>();
					
					for ( int i=0;i<offers_to_generate;i++){

						listener.getOffer( 
							info_hash, read_timeout - 5*1000,
							new WebRTCProvider.OfferListener() {
								
								boolean done = false;
								
								@Override
								public void gotOffer(Offer offer) {
									synchronized( sem ){
										if ( done ){
											return;
										}
										done = true;
										offers.add( offer );
									}
									sem.release();
								}
								
								@Override
								public void failed() {
									synchronized( sem ){
										if ( done ){
											return;
										}
										done = true;
									}
									sem.release();
								}
							});
					}
					
					for ( int i=0;i<offers_to_generate;i++){
						
						sem.reserve();
					}
					
					if ( offers_to_generate > 0 && offers.size() == 0 ){
						
						throw( new IOException( "WebTorrent proxy appears to be unavailable" ));
						
					}else{
						
						long	elapsed = SystemTime.getMonotonousTime() - start;
						
						if (offers_to_generate > 0) {
                            long batches = offerBatches.incrementAndGet();
                            if (batches <= 3 || batches % 100 == 0) {
                                diagnostic("offers-generated", ws_url,
                                    "requested=" + offers_to_generate + " ready=" + offers.size()
                                    + " batches=" + batches + " elapsedMs=" + elapsed);
                            }
                        }
					}
				}
			}
			
			if ( offers != null || scrape || is_stop ){
								
					// roll it by hand, something messes up if we try and use JSONUtils :(
				
				String offer_str = "";
				
				if ( offers != null ){
					
					for ( Offer offer: offers ){
						
						offer_str += 
							(offer_str.length()==0?"":",") + 
							"{\"offer\":{\"type\":\"offer\",\"sdp\":\"" + WebTorrentPlugin.encodeForJSON( offer.getSDP()) + "\"},\"offer_id\":\"" + WebTorrentPlugin.encodeForJSON( offer.getOfferID()) + "\"}";
					}
				}
								
					// on 2016/04/08 I discovered that the tracker protocol had been rewritten to pass "announce" and "scrape" as the action rather than just 
					// the number 1 (and we added 2 for scrape). 
					// Server code also updated to handle multiple info-hashes (and a single one too so we're good for the moment mebe)
				
	        	final String announce = 
	        			"{\"numwant\":" + numwant + 
	        			",\"uploaded\":" + uploaded + 
	        			",\"downloaded\":" + downloaded + 
	        			",\"left\":" + left + 
	        			",\"action\":" + (scrape?"\"scrape\"":"\"announce\"") +
	        			(event==null?"": ( ",\"event\":\"" + event + "\"" )) + 
	        			",\"info_hash\":\"" + WebTorrentPlugin.encodeForJSON( info_hash ) + "\"" +
	        			",\"peer_id\":\"" + WebTorrentPlugin.encodeForJSON( peer_id ) + "\"" +
	        			(scrape?"":( ",\"offers\":[" + offer_str + "]" )) +
	        			"}";
	        		        			
	        	if (!scrape) {
	        		if (is_stop) {
                    announce_registry.remove(ws_url, info_hash);
                    diagnostic("registration-stopped", ws_url, "activeRegistrationRemoved=true");
	        		} else {
	        			announce_registry.register(ws_url, info_hash, announce);
	        		}
	        	}
	        	trace( "sending: " + announce );
	        	
	        	ClientSession	client_session = null;
				
				synchronized( client_sessions ){
					
					client_session = client_sessions.get( ws_url );
					
					if ( client_session != null && !client_session.isOpen()){
						
						client_sessions.remove( ws_url );
                        diagnostic("stale-socket-evicted", ws_url, "closed=true");
                        client_session = null;
					}
				}
			
				if ( client_session != null ){
					
					trace( "announce: old connection to " + ws_url + ": " + announce);
					
					client_session.send( announce );
					
				}else{
					
		            // Avoid duplicate handshakes when torrents share a tracker.
		            synchronized (connection_gate.lockFor(ws_url)) {
		            	synchronized (client_sessions) {
		            		ClientSession other = client_sessions.get(ws_url);
		            		if (other != null && other.isOpen()) {
		            			client_session = other;
		            		}
		            	}
		            	if (client_session != null) {
                        diagnostic("connection-reused-after-gate", ws_url, "duplicateHandshakeAvoided=true");
                        client_session.send(announce);
		            	} else {
		            diagnostic("connect-start", ws_url, "serialized=true");
                    ClientManager client = ClientManager.createClient();
                    // Bound TLS and HTTP upgrade negotiation as well as the announce wait.
                    // The read timeout remains the overall caller budget.
                    int handshakeTimeout = (int) Math.min(10_000L,
                        Math.max(1_000L, read_timeout - 5_000L));
                    client.getProperties().put(ClientProperties.HANDSHAKE_TIMEOUT,
                        handshakeTimeout);
		            
		            if ( ws_url.toLowerCase( Locale.US ).startsWith( "wss")){
		            	
			            TrustManager[] trustAllCerts = SESecurityManager.getAllTrustingTrustManager();				
						
						SSLContext sc = SSLContext.getInstance("SSL");
							
						sc.init( null, trustAllCerts, RandomUtils.SECURE_RANDOM );
			         
				        SSLEngineConfigurator sslEngineConfigurator = new SSLEngineConfigurator(sc );
				        
			            client.getProperties().put( ClientProperties.SSL_ENGINE_CONFIGURATOR, sslEngineConfigurator);
		            }
		            
		            ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();
		            
                    final java.util.concurrent.atomic.AtomicBoolean abandoned =
                        new java.util.concurrent.atomic.AtomicBoolean();
                    final java.util.concurrent.atomic.AtomicReference<Session> opened =
                        new java.util.concurrent.atomic.AtomicReference<>();
                    java.util.concurrent.Future<Session> connecting =
                        client.asyncConnectToServer(
		            		new Endpoint() 
		            		{
				                @Override
				                public void 
				                onOpen(
				                	final Session 		session, 
				                	EndpointConfig 		config) 
				                {
                                    opened.set(session);
                                    if (abandoned.get()) {
                                        try { session.close(); } catch (Exception ignored) { }
                                        return;
                                    }
                                    try{
				                    	
				                        session.addMessageHandler(PongMessage.class, new MessageHandler.Whole<PongMessage>() {
				                            @Override
				                            public void onMessage(PongMessage pong) {
				                                synchronized (client_sessions) {
				                                    ClientSession current = client_sessions.get(ws_url);
				                                    if (current != null && current.getSession() == session) {
				                                        current.pongReceived();
				                                    }
				                                }
				                            }
				                        });
				                        session.addMessageHandler(new MessageHandler.Whole<String>() {
	
				                            @Override
				                            public void 
				                            onMessage(
				                            	String message ) 
				                            {
				                            	trace("Received message from " + ws_url + ", offers=" + (offers==null?null:offers.size()) + ": " + message);
	
				                            	Map map = JSONUtils.decodeJSON( message );
				                            	if (map != null && map.containsKey("failure reason")) {
				                            		synchronized (client_sessions) {
				                            			ClientSession current = client_sessions.get(ws_url);
				                            			if (current != null && current.getSession() == session) {
				                            				Object hashValue = map.get("info_hash");
				                            				if (hashValue instanceof String) {
				                            					current.announceReceived(
				                            						((String) hashValue).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), map);
				                            				} else {
				                            					current.announceFailed(map);
				                            				}
				                            			}
				                            		}
				                            	}
				                            			                            	
				                            	if ( map.containsKey( "answer" )){
				                            	
				                            		try{
				                            			Map answer = (Map)map.get( "answer" );
				                            		
				                            			String offer_id = (String)map.get( "offer_id" );
				                            		
				                            			String hash_str = (String)map.get( "info_hash" );
					                            		
					                            		byte[] hash = hash_str.getBytes( "ISO-8859-1" );
					                            		
				                            			String sdp = (String)answer.get( "sdp" );
				                            		
				                            			listener.gotAnswer( hash, offer_id, sdp );
                                                    long answered = answersReceived.incrementAndGet();
                                                    if (answered <= 3 || answered % 100 == 0) {
                                                        diagnostic("answer-delivered", ws_url, "count=" + answered);
                                                    }
				                            			
				                            		}catch( Throwable e ){
				                            			
				                            			Debug.out( e );
				                            		}
				                            	}else if ( map.containsKey( "offer" )){
				                            		
				                            		try{
					                            		Map offer = (Map)map.get( "offer" );
					                            		
					                            		String offer_id = (String)map.get( "offer_id" );
					                            		
					                            		final String to_peer_id = (String)map.get( "peer_id" );
					                            		
					                            		final String info_hash = (String)map.get( "info_hash" );
					                            		
					                            		final byte[] hash = info_hash.getBytes( "ISO-8859-1" );
				                            							                            		
				                            			String sdp = (String)offer.get( "sdp" );
				                            		
				                            			final byte[]	peer_id;
				                            		
				                            			synchronized( hash_to_peer_id_map ){
				                            				
				                            				peer_id = hash_to_peer_id_map.get( hash );
				                            			}
				                            			
				                            			if ( peer_id != null ){
				                            				
					                            			long received = offersReceived.incrementAndGet();
                                                    if (received <= 3 || received % 100 == 0) {
                                                        diagnostic("offer-received", ws_url, "count=" + received);
                                                    }
                                                    listener.gotOffer( 
					                            				hash, 
					                            				offer_id, 
					                            				sdp,
					                            				new WebRTCProvider.AnswerListener() {
																	
																	@Override
																	public void 
																	gotAnswer(
																		Answer answer ) 
																	{
																		String answer_str = "{\"action\":\"announce\",\"answer\":{\"type\":\"answer\",\"sdp\":\"" + 
																				WebTorrentPlugin.encodeForJSON( answer.getSDP()) + "\"}," + 
																				"\"offer_id\":\"" + WebTorrentPlugin.encodeForJSON( answer.getOfferID()) + "\"," + 
																				"\"peer_id\":\"" + WebTorrentPlugin.encodeForJSON( peer_id ) + "\"," + 
																				"\"to_peer_id\":\"" + WebTorrentPlugin.encodeForJSON( to_peer_id ) + "\"," + 
																				"\"info_hash\":\"" + WebTorrentPlugin.encodeForJSON( hash ) + "\"" + 
																				"}";
	
																		trace( answer_str );
																		
																		try{
																			ClientSession active;
												synchronized (client_sessions) {
													active = client_sessions.get(ws_url);
												}
												if (active == null || active.getSession() != session) {
													throw new IOException("WebSocket tracker session was replaced");
												}
												active.send(answer_str);
																			
																			trace( "    answer sent" );
																			
																		 }catch( Throwable e ){
										                            			
											                            	//	Debug.out( e );
																			 
																			 trace( "Failed to send answer: " + Debug.getNestedExceptionMessage( e ));
																		 }
																	}
																	
																	@Override
																	public void failed()
																	{
																	}
																});
				                            			}else{
				                            				
				                            				trace( "Peer id for " + ByteFormatter.encodeString( hash ) + " not found" );
				                            			}
				                            		}catch( Throwable e ){
				                            			
				                            			Debug.out( e );
				                            		}
				                            		
				                            	}else if ( map.containsKey( "complete" )){
				                            		
				                            		synchronized( client_sessions ){
							                    		 
							                    		 ClientSession s = client_sessions.get( ws_url );
								                		 
								                		 if ( s != null && s.getSession() == session ){
								                			 
								                			 try{
							                            		String hash_str = (String)map.get( "info_hash" );

								                            	byte[] hash = hash_str.getBytes( "ISO-8859-1" );
								                			 
								                            	s.announceReceived( hash, map );
								                            	
								                			 }catch( Throwable e ){
							                            			
							                            		Debug.out( e );
							                            	}
								                		 }
								                	 }
				                            	}else if ( map.containsKey( "files" )){
				                            		
				                            			// scrape response
				                            		
				                            		Map<String,Map> files = (Map<String,Map>)map.get( "files" );
				                            		
				                            		synchronized( client_sessions ){
							                    		 
							                    		 ClientSession s = client_sessions.get( ws_url );
								                		 
								                		 if ( s != null && s.getSession() == session ){

								                			 for ( String info_hash: files.keySet()){

								                				 try{
								                					 
								                					 byte[] hash = info_hash.getBytes( "ISO-8859-1" );
								                					 
								                					 s.announceReceived( hash, map );
								                					 
								                				 }catch( Throwable e ){

								                					 Debug.out( e );
								                				 }
								                			 }
								                		 }
				                            		}
				                            	}
				                            }
				                        });
				                      		
				                        trace( "announce: new connection to " + ws_url + announce );
				                        
				                        diagnostic("socket-open", ws_url, "handlersReady=true");
                                        // Initial announce is sent after this session is registered below.
				                        
				                    }catch( Exception e ){
				                    	
				                    	 synchronized( client_sessions ){
				                    		 
				                    		 ClientSession s = client_sessions.get( ws_url );
					                		 
					                		 if ( s != null && s.getSession() == session ){
					                			 
					                			 s.close();
					                			 
					                			 client_sessions.remove( ws_url );
					                		 }
				                    	 }
				                    }
				                }
				                
				                @Override
				                public void onClose(Session session, CloseReason reason) {
                                    diagnostic("socket-closed", ws_url,
                                        "code=" + (reason == null ? "unknown" : reason.getCloseCode().getCode()));
                                    synchronized (client_sessions) {
				                        ClientSession current = client_sessions.get(ws_url);
				                        if (current != null && current.getSession() == session) {
				                            client_sessions.remove(ws_url);
				                            current.close();
				                        }
				                    }
				                }

				                @Override
				                public void 
				                onError(
				                	Session 	session, 
				                	Throwable 	e )
				                {
                                    diagnostic("socket-error", ws_url,
                                        "type=" + (e == null ? "unknown" : e.getClass().getSimpleName()));
				                	 synchronized( client_sessions ){
				                		 
				                		 ClientSession s = client_sessions.get( ws_url );
				                		 
				                		 if ( s != null && s.getSession() == session ){
				                			 
				                			 s.close();
				                			 
				                			 client_sessions.remove( ws_url );
				                		 }
				                	 }
				                }
                            }, cec, new URI( ws_url ));
                    // An endpoint may open just as the caller's deadline expires.
                    // Close any such late session rather than leave an untracked socket.
                    Runnable abortHandshake = () -> {
                        abandoned.set(true);
                        connecting.cancel(true);
                        Session late = opened.get();
                        if (late != null) {
                            try { late.close(); } catch (Exception ignored) { }
                        }
                    };
                    Session session;
                    try {
                        session = connecting.get(handshakeTimeout, TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        abortHandshake.run();
                        diagnostic("handshake-timeout", ws_url, "timeoutMs=" + handshakeTimeout);
                        throw new IOException("WebSocket tracker handshake timed out", e);
                    } catch (java.util.concurrent.ExecutionException e) {
                        abortHandshake.run();
                        throw new IOException("WebSocket tracker handshake failed", e.getCause());
                    } catch (InterruptedException e) {
                        abortHandshake.run();
                        Thread.currentThread().interrupt();
                        throw new IOException("WebSocket tracker handshake interrupted", e);
                    }

		            client_session = new ClientSession( session, ws_url );
		            
		            ClientSession existing;
                    synchronized( client_sessions ){
                        existing = client_sessions.put( ws_url, client_session );
		            	
		            	if ( existing != null ){
		            		
		            		try{
		            			existing.close();
		            			
		            		}catch( Throwable e ){
		            			
		            		}
		            	}
		            }
		            diagnostic("connection-registered", ws_url, "replaced=" + (existing != null));
                    client_session.startHeartbeat();
                    client_session.send(announce);
                    // Restore previously registered torrents without creating new offers.
                    List<String> refreshes = announce_registry.refreshExcept(ws_url,
                        (!scrape && !is_stop) ? info_hash : null);
                    for (String refresh : refreshes) {
                        client_session.send(refresh);
                    }
                    diagnostic("announces-restored", ws_url,
                        "count=" + refreshes.size() + " offersRegenerated=false");
		            }
		            }
				}
				
				Map reply = client_session.waitForAnnounce( info_hash, read_timeout - 5*1000 );
				
				if ( reply == null ){
					// Prevent a timed-out, apparently open socket from being reused.
					boolean removed;
					synchronized (client_sessions) {
						removed = client_sessions.get(ws_url) == client_session;
						if (removed) client_sessions.remove(ws_url);
					}
					if (removed) client_session.close();
                    diagnostic("announce-timeout", ws_url,
                        "evicted=" + removed + " timeoutMs=" + (read_timeout - 5000));
                    throw new IOException("Tracker announce timed out");
				}
				if ( reply.containsKey("failure reason") ){
                    diagnostic("announce-rejected", ws_url, "trackerFailure=true");
                    throw new IOException("Tracker rejected announce: " + reply.get("failure reason"));
				}
				
				byte[] reply_bytes;
				
				if ( scrape ){
					
					if ( reply.containsKey( "files" )){
						
						Map<String,Object>	root = new HashMap<>();
						
						root.putAll( reply );
						
						ByteEncodedKeyHashMap<String, Object>	files = new ByteEncodedKeyHashMap<>();
						
						files.putAll((Map)reply.get( "files" ));
						
						root.put( "files", files);
												
						reply_bytes = BEncoder.encode( root );
						
					}else{
							// old style before tracker protocol supported scrape
						
						Map<String,Object>	root = new HashMap<>();
						
						ByteEncodedKeyHashMap<String, Object>	files = new ByteEncodedKeyHashMap<>();
						
						root.put( "files", files );
						
						files.put( new String( info_hash,Constants.BYTE_ENCODING_CHARSET ), reply );
						
						reply_bytes = BEncoder.encode( root );
					}
					
				}else{
					
					reply_bytes = BEncoder.encode( reply );
				}
				
				
				String[] reply_lines = {
						
						"HTTP/1.1 200 OK",
						"Content-Length: " + reply_bytes.length,
						"Connection: close"
				};
				
				for ( String str: reply_lines ){
					
					os.write((str + "\r\n" ).getBytes( "ISO-8859-1" ));
				}
				
				os.write( "\r\n" .getBytes( "ISO-8859-1" ));
				
				os.write( reply_bytes );
				
			}else{
				
				throw( new Exception( "WebSocket proxy offer not available" ));
			}
		 }catch( Throwable e ){
            diagnostic("proxy-request-failed", trackerLogUrl,
                "type=" + e.getClass().getSimpleName());
            Map failure = new HashMap();
			
			failure.put( "failure reason", Debug.getNestedExceptionMessage( e ));
			
			try{
				byte[] x = BEncoder.encode( failure );
							
				String[] reply_lines = {
				
						"HTTP/1.1 200 OK",
						"Content-Length: " + x.length,
						"Connection: close"
				};
				
				for ( String str: reply_lines ){
					
					os.write((str + "\r\n" ).getBytes( "ISO-8859-1" ));
				}
				
				os.write( "\r\n" .getBytes( "ISO-8859-1" ));
				
				os.write( x );
				
			}catch( Throwable f ){
				
				//Debug.printStackTrace(f);
			}
		}
	}
	
	public void destroy() {
        diagnostic("proxy-shutdown", null, "activeSessions=" + client_sessions.size());
        synchronized( client_sessions ){
		 
			 for ( ClientSession s: client_sessions.values()){
			 
				 try{
					 s.close();
				 
				 }catch( Throwable e ){
				 }
			 }
			 
			 client_sessions.clear();
		 }
		 announce_registry.clear();
		 ping_executor.shutdownNow();
	}
    
    public interface
    Listener
    {
    	public void
    	getOffer(
    		byte[]							hash,
    		long							timeout,
    		WebRTCProvider.OfferListener	offer_listener );
    	
    	public void
    	gotAnswer(
    		byte[]		hash,
    		String		offer_id,
    		String		sdp )
    		
    		throws Exception;
    	
    	public void
    	gotOffer(
    		byte[]							hash,
    		String							offer_id,
    		String							sdp,
    		WebRTCProvider.AnswerListener	listener )
    		
    		throws Exception;
    }
    
    private class
    ClientSession
    {
    	private Session		session;
    	private boolean terminated;
    	private final String ws_url;
    	private final TrackerPingState ping_state = new TrackerPingState();
    	private ScheduledFuture<?> ping_task;
    	private final TrackerSocketWriter writer;
        private long sentMessages;
        private long receivedPongs;
        private long sendFailures;
        private long responseCount;
        private long staleResponseCount;
    	
    	private ByteArrayHashMap<AnnounceData>	announce_map = new ByteArrayHashMap<>();
    	    	
    	protected
    	ClientSession(Session _session) {
    		this(_session, null);
    	}

    	protected
    	ClientSession(Session _session, String _ws_url) {
    		session = _session;
    		ws_url = _ws_url;
    		writer = new TrackerSocketWriter(_session, writerTimeoutMs);
    	}

    	private void startHeartbeat() {
    		ping_task = ping_executor.scheduleAtFixedRate(() -> {
    			if (!session.isOpen()) {
                    diagnostic("heartbeat-socket-closed", ws_url, "evicting=true");
                    invalidate();
    				return;
    			}
    			if (!ping_state.startPing(SystemTime.getMonotonousTime())) {
    				return;
    			}
    			ping_executor.schedule(() -> {
    				if (ping_state.isExpired(SystemTime.getMonotonousTime(), pongTimeoutMs)) {
                        diagnostic("heartbeat-pong-timeout", ws_url, "timeoutMs=" + pongTimeoutMs);
                        invalidate();
    				}
    			}, pongTimeoutMs, TimeUnit.MILLISECONDS);
    			try {
    				session.getBasicRemote().sendPing(ByteBuffer.allocate(0));
                } catch (Exception e) {
                    diagnostic("heartbeat-send-failed", ws_url, "type=" + e.getClass().getSimpleName());
                    invalidate();
    			}
    		}, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS);
    	}

    	private void pongReceived() {
            ping_state.pong();
            long count = ++receivedPongs;
            if (count == 1 || count % 100 == 0)
                diagnostic("heartbeat-pong", ws_url, "count=" + count);
        }

    	private void invalidate() {
    		synchronized (client_sessions) {
    			if (client_sessions.get(ws_url) == this) {
    				client_sessions.remove(ws_url);
    			}
    		}
    		close();
    	}
    	
    	private void
    	send(
    		String		str )
    		
    		throws Exception
    	{
    		try {
                writer.send(str);
                long count = ++sentMessages;
                if (count == 1 || count % 100 == 0)
                    diagnostic("send-ok", ws_url, "count=" + count);
            } catch (Exception e) {
                sendFailures++;
                diagnostic("bounded-send-failed", ws_url,
                    "type=" + e.getClass().getSimpleName() + " errors=" + sendFailures);
                throw e;
            }
    	}
    	
    	private Session
    	getSession()
    	{
    		return( session );
    	}
    	
    	private boolean
    	isOpen()
    	{
    		return( session.isOpen());
    	}
    	
    	private void announceFailed(Map failure) {
            diagnostic("tracker-failure-received", ws_url, "pendingEntries=" + announce_map.size());
            synchronized( this ){
    			for ( AnnounceData ad: announce_map.values() ){
    				ad.last_announce = failure;
    				ad.last_announce_time = SystemTime.getMonotonousTime();
    				if ( ad.announce_sem != null ){
    					ad.announce_sem.releaseForever();
    					ad.announce_sem = null;
    				}
    			}
    		}
    	}

    	private void
    	announceReceived(
    		byte[]		hash,
    		Map			map )
    	{
            long count = ++responseCount;
            if (count == 1 || count % 100 == 0) {
                diagnostic("announce-response", ws_url,
                    "count=" + count + " rejected=" + map.containsKey("failure reason"));
            }
    		synchronized( this ){
    			
    			AnnounceData	ad = announce_map.get( hash );
    			
    			if ( ad == null ){
    				
    				ad = new AnnounceData();
    				
    				announce_map.put( hash, ad );
    			}
    			
    			ad.last_announce 		= map;
    			ad.last_announce_time	= SystemTime.getMonotonousTime();
    			
    			if ( ad.announce_sem != null ){
    				
    				ad.announce_sem.releaseForever();
    				
    				ad.announce_sem = null;
    			}
    		}
    	}
    	
    	private Map
    	waitForAnnounce(
    		byte[]		hash,
    		long		timeout )
    	{
    		AnnounceData	ad;
    		AESemaphore 	sem;
    		
    		synchronized( this ){
    			if ( terminated ) return null;
    			ad = announce_map.get( hash );
    			
    			if ( ad == null ){
    				
    				ad = new AnnounceData();
    				
    				announce_map.put( hash, ad );
    			}
    			
    			if (ad.last_announce != null) {
                    long age = SystemTime.getMonotonousTime() - ad.last_announce_time;
                    if (age < 10000) {
                        return ad.last_announce;
                    }
                    long ignored = ++staleResponseCount;
                    if (ignored <= 3 || ignored % 100 == 0) {
                        diagnostic("stale-response-ignored", ws_url,
                            "count=" + ignored + " ageMs=" + age);
                    }
                }
    			
    			if ( ad.announce_sem == null ){
    				
    				ad.announce_sem = new AESemaphore( "" );
    			}
    			
    			sem = ad.announce_sem;
    		}
    		
    		if (!sem.reserve(timeout)) {
    			return null;
    		}
    		synchronized( this ){
    			return( terminated ? null : ad.last_announce );
    		}
    	}
    	
    	private void
    	close()
    	{
    		if (ping_task != null) {
    			ping_task.cancel(false);
    		}
    		synchronized( this ){
                if (terminated) return;
                terminated = true;
                int pending = 0;
                for (AnnounceData ad : announce_map.values())
                    if (ad.announce_sem != null) pending++;
                diagnostic("session-cleanup", ws_url,
                    "pendingReleased=" + pending + " sent=" + sentMessages
                    + " pongs=" + receivedPongs + " sendFailures=" + sendFailures);
                for ( AnnounceData ad: announce_map.values() ){
    				ad.last_announce = null;
    				if ( ad.announce_sem != null ){
    					ad.announce_sem.releaseForever();
    					ad.announce_sem = null;
    				}
    			}
    		}
    		try{
    			session.close();
    			
    		}catch( Throwable e ){
    			
    		}
    	}
    }
    
    private class
    AnnounceData
    {
    	private Map			last_announce;
    	private long		last_announce_time;
    	private AESemaphore	announce_sem;

    }
    
    private void
    trace(
    	String		str )
    {
    	if ( TRACE ){
    		System.out.println( str );
    	}
    }
}
