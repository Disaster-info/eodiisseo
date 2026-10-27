package com.disasterinfo.eodiisseo.auth.config;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.summary.SummaryException;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import com.disasterinfo.eodiisseo.notification.NotificationContracts;
import com.disasterinfo.eodiisseo.notification.NotificationException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Implements other owners' existing interfaces without editing their feature folders. */
@Configuration @Profile("!test")
@ConditionalOnProperty(name="AUTH_BRIDGES_ENABLED",havingValue="true")
public class AuthenticationPortsConfiguration {
    @Bean public DashboardSessionVerifier dashboardSessionVerifier(SessionAccessService sessions){
        return (member,session)->{try{sessions.requireActiveSession(member,session);}catch(ApiProblem failure){throw new DashboardException(DashboardException.Reason.UNAUTHENTICATED,"An active service session is required.");}};
    }
    @Bean public SummaryAuthPort summaryAuthPort(SessionAccessService sessions){
        return (member,session)->{try{sessions.requireActiveSession(member,session);}catch(ApiProblem failure){throw new SummaryException(SummaryException.Reason.UNAUTHENTICATED);}};
    }
    @Bean public ChatAuthenticationPort chatAuthenticationPort(SessionAccessService sessions){
        return new ChatAuthenticationPort(){
            public void requireActiveSession(UUID member,UUID session){try{sessions.requireActiveSession(member,session);}catch(ApiProblem failure){throw unauthorized();}}
            public void runWithActiveSession(UUID member,UUID session,Runnable action){try{sessions.runWithActiveSession(member,session,action);}catch(ApiProblem failure){throw unauthorized();}}
            private ChatException unauthorized(){return new ChatException(ChatException.Reason.UNAUTHENTICATED,"An active service session is required.");}
        };
    }
    @Bean public NotificationContracts.Authentication notificationAuthentication(SessionAccessService sessions){
        return new NotificationContracts.Authentication(){
            public void requireActiveSession(NotificationContracts.MemberSession session){
                if(session==null)throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);
                try{sessions.requireActiveSession(session.memberId(),session.serviceSessionId());}catch(ApiProblem failure){throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);}
            }
            public boolean isActiveMember(UUID member){return sessions.isActiveMember(member);}
        };
    }
}
