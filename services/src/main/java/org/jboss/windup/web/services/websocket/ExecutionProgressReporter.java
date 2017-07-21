package org.jboss.windup.web.services.websocket;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.event.Observes;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.ObjectMessage;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.jboss.windup.web.services.KeycloakAuthenticationException;
import org.jboss.windup.web.services.KeycloakAuthenticator;
import org.jboss.windup.web.services.messaging.AbstractMDB;
import org.jboss.windup.web.services.model.WindupExecution;
import org.json.JSONException;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author <a href="mailto:dklingenberg@gmail.com">David Klingenberg</a>
 */
@ServerEndpoint("/websocket/execution-progress/{executionId}")
public class ExecutionProgressReporter extends AbstractMDB implements Serializable
{

    // server endpoint (per JVM)
    private static final Map<Long, Set<Session>> sessions = Collections.synchronizedMap(new HashMap<Long, Set<Session>>());

    // private static final Set<Session> sessions = Collections.synchronizedSet(new HashSet<Session>());
    private static final Logger LOG = Logger.getLogger(ExecutionProgressReporter.class.getName());

    @OnClose
    public void onClose(Session session, CloseReason reason, @PathParam("executionId") Long executionId)
    {
        if (sessions.containsKey(executionId))
        {
            Set<Session> executionSessions = sessions.get(executionId);

            executionSessions.remove(session);

            if (executionSessions.isEmpty())
            {
                sessions.remove(executionId);
            }
        }
    }

    @OnMessage
    public void onMessage(Session session, String message, @PathParam("executionId") Long executionId)
    {
        authenticate(session, message);

        if (!sessions.containsKey(executionId))
        {
            sessions.put(executionId, Collections.synchronizedSet(new HashSet<>()));
        }

        sessions.get(executionId).add(session);
    }

    private void authenticate(Session session, String message)
    {
        try
        {
            String token = null;
            try
            {
                JSONObject jsonObject = new JSONObject(message);
                JSONObject authenticationObject = jsonObject.getJSONObject("authentication");
                if (authenticationObject == null)
                    throw new KeycloakAuthenticationException("Authentication message did not contain a token");

                token = authenticationObject.getString("token");
                if (token == null)
                    throw new KeycloakAuthenticationException("Authentication message did not contain a token");
            }
            catch (JSONException e)
            {
                throw new KeycloakAuthenticationException("Unable to parse message due to: " + e.getMessage());
            }

            KeycloakAuthenticator.validateToken(token);
        }
        catch (KeycloakAuthenticationException e)
        {
            LOG.warning("Received a request with an invalid token");
            try
            {
                session.close(new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, e.getLocalizedMessage()));
            }
            catch (IOException e1)
            {
                LOG.warning(e.getMessage());
            }
        }
    }

    /**
     * Listens to JMS events from StatusUpdateMDB
     * 
     * @see org.jboss.windup.web.services.messaging.StatusUpdateMDB
     * 
     * @param msg
     */
    public void onJMSMessage(@Observes @WSJMSMessage Message msg)
    {
        if (!validatePayload(WindupExecution.class, msg))
        {
            return;
        }

        try
        {
            WindupExecution execution = (WindupExecution) ((ObjectMessage) msg).getObject();

            for (Session session : sessions.getOrDefault(execution.getId(), new HashSet<>()))
            {
                ObjectMapper objectMapper = new ObjectMapper();
                String serializedObject = objectMapper.writeValueAsString(execution);

                session.getBasicRemote().sendText(serializedObject);
            }
        }
        catch (IOException | JMSException ex)
        {
            LOG.log(Level.SEVERE, "Exception during receiving JMS message", ex);
        }
    }
}