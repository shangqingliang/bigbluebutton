/**
* BigBlueButton open source conferencing system - http://www.bigbluebutton.org/
*
* Copyright (c) 2010 BigBlueButton Inc. and by respective authors (see below).
*
* This program is free software; you can redistribute it and/or modify it under the
* terms of the GNU Lesser General Public License as published by the Free Software
* Foundation; either version 2.1 of the License, or (at your option) any later
* version.
*
* BigBlueButton is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
* PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License along
* with BigBlueButton; if not, see <http://www.gnu.org/licenses/>.
* 
*/
package org.bigbluebutton.conference;

import java.util.Iterator;
import java.util.Set;
import org.red5.server.api.Red5;import org.bigbluebutton.conference.messages.in.meetings.AllMeetingsStop;
import org.bigbluebutton.conference.messages.in.meetings.MeetingEnd;
import org.bigbluebutton.conference.messages.in.meetings.MeetingStart;
import org.bigbluebutton.conference.service.recorder.RecorderApplication;
import org.red5.logging.Red5LoggerFactory;
import org.red5.server.adapter.IApplication;
import org.red5.server.adapter.MultiThreadedApplicationAdapter;
import org.red5.server.api.IConnection;
import org.red5.server.api.IContext;
import org.red5.server.api.scope.IScope;
import org.slf4j.Logger;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.AbstractApplicationContext;

public class BigBlueButtonApplication extends MultiThreadedApplicationAdapter {
	private static Logger log = Red5LoggerFactory.getLogger(BigBlueButtonApplication.class, "bigbluebutton");

	private RecorderApplication recorderApplication;
	private AbstractApplicationContext appCtx;
	private ConnectionInvokerService connInvokerService;
	
	private String version;
	private BigBlueButton bbb;
	
	@Override
    public boolean appStart(IScope app) {
        log.debug("Starting BigBlueButton version " + version); 
        IContext context = app.getContext();
        appCtx = (AbstractApplicationContext) context.getApplicationContext();
        appCtx.addApplicationListener(new ShutdownHookListener());
        appCtx.registerShutdownHook();
        return super.appStart(app);
    }
    
	@Override
    public void appStop(IScope app) {
        log.debug("Stopping BigBlueButton version " + version);
        super.appStop(app);
    }
    
	@Override
    public boolean roomStart(IScope room) {
    	log.debug("Starting room [" + room.getName() + "].");
    	connInvokerService.addScope(room.getName(), room);
    	return super.roomStart(room);
    }	
	
	@Override
    public void roomStop(IScope room) {
    	String meetingID = room.getName();
    	log.debug("Stopping room [" + meetingID + "].");
    	
    	super.roomStop(room);
    	
    	bbb.accept(new MeetingEnd(meetingID));
    	
		recorderApplication.destroyRecordSession(getBbbSession().getSessionName());
		connInvokerService.removeScope(meetingID);
		
		log.debug("Stopped room [" + meetingID + "].");
    }
    
	@Override
	public boolean roomConnect(IConnection connection, Object[] params) {
        String remoteHost = Red5.getConnectionLocal().getRemoteAddress();
        int remotePort = Red5.getConnectionLocal().getRemotePort();
        String username = ((String) params[0]).toString();
        String role = ((String) params[1]).toString();
        String conference = ((String)params[2]).toString();

        /*
         * Convert the id to Long because it gets converted to ascii decimal
         * equivalent (i.e. zero (0) becomes 48) if we don't.
         */
        long clientID = Long.parseLong(Red5.getConnectionLocal().getClient().getId());
        String sessionName = connection.getScope().getName();
        log.info("[clientid=" + clientID + "] connected from " + remoteHost + ":" + remotePort + ".");
        
        String voiceBridge = ((String) params[4]).toString();
		String meetingID = sessionName;
		assert recorderApplication != null;
		boolean record = (Boolean)params[5];
		log.debug("record value - [" + record + "]"); 

    	String externalUserID = ((String) params[6]).toString();
    	String internalUserID = ((String) params[7]).toString();
    	    	
		if (record == true) {
			recorderApplication.createRecordSession(sessionName);
		}
			
    	BigBlueButtonSession bbbSession = new BigBlueButtonSession(sessionName, clientID, internalUserID,  username, role, 
    			conference, meetingID, voiceBridge, record, externalUserID);
        connection.setAttribute(Constants.SESSION, bbbSession);        
        
        String debugInfo = "internalUserID=" + internalUserID + ",username=" + username + ",role=" +  role + ",conference=" + conference + "," + 
        					"session=" + sessionName + ",voiceConf=" + voiceBridge + ",room=" + meetingID + ",externalUserid=" + externalUserID;
		log.debug("User [{}] connected to room [{}]", debugInfo, meetingID); 
		
		bbb.accept(new MeetingStart(meetingID));
		
        super.roomConnect(connection, params);
        
        connInvokerService.addConnection(bbbSession.getInternalUserID(), connection);
    	return true;
	}

	@Override
	public void roomDisconnect(IConnection conn) {
        String remoteHost = Red5.getConnectionLocal().getRemoteAddress();
        int remotePort = Red5.getConnectionLocal().getRemotePort();    	
        String clientId = Red5.getConnectionLocal().getClient().getId();
    	log.info("[clientid=" + clientId + "] disconnnected from " + remoteHost + ":" + remotePort + ".");
    	
    	connInvokerService.removeConnection(getBbbSession().getInternalUserID());
    	
		BigBlueButtonSession bbbSession = (BigBlueButtonSession) Red5.getConnectionLocal().getAttribute(Constants.SESSION);
		log.info("User [" + bbbSession.getUsername() + "] disconnected from room [" + bbbSession.getRoom() +"]");
		super.roomDisconnect(conn);
	}
	
	public String getMyUserId() {
		BigBlueButtonSession bbbSession = (BigBlueButtonSession) Red5.getConnectionLocal().getAttribute(Constants.SESSION);
		assert bbbSession != null;
		//return Long.toString(bbbSession.getClientID());
		return bbbSession.getInternalUserID();
	}
	
	public void setRecorderApplication(RecorderApplication a) {
		recorderApplication = a;
	}
	
	public void setApplicationListeners(Set<IApplication> listeners) {
		Iterator<IApplication> iter = listeners.iterator();
		while (iter.hasNext()) {
			super.addListener((IApplication) iter.next());
		}
	}
	
	public void setBigBlueButton(BigBlueButton bbb) {
		this.bbb = bbb;
	}
	
	public void setVersion(String v) {
		version = v;
	}
	
	private BigBlueButtonSession getBbbSession() {
		return (BigBlueButtonSession) Red5.getConnectionLocal().getAttribute(Constants.SESSION);
	}

	public void setConnInvokerService(ConnectionInvokerService connInvokerService) {
		System.out.print("Setting conn invoket service!!!!");
		this.connInvokerService = connInvokerService;
	}
	
	private class ShutdownHookListener implements ApplicationListener<ApplicationEvent> {

		@Override
		public void onApplicationEvent(ApplicationEvent event) {
			if (event instanceof org.springframework.context.event.ContextStoppedEvent) {
				log.info("Received shutdown event. Red5 is shutting down. Destroying all rooms.");
				bbb.accept(new AllMeetingsStop());
			}			
		}
		
	}
}
