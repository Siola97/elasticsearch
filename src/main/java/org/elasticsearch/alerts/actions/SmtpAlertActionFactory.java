/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.actions;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.alerts.Alert;
import org.elasticsearch.alerts.ConfigurableComponentListener;
import org.elasticsearch.alerts.ConfigurationManager;
import org.elasticsearch.alerts.triggers.TriggerResult;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.support.XContentMapValues;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class SmtpAlertActionFactory implements AlertActionFactory, ConfigurableComponentListener {

    private static final String GLOBAL_EMAIL_CONFIG = "email";

    private static final String PORT_SETTING = "alerts.action.snpt.server.port";
    private static final String SERVER_SETTING = "alerts.action.email.server.name";
    private static final String FROM_SETTING = "alerts.action.email.from.address";
    private static final String PASSWD_SETTING = "alerts.action.email.from.passwd";

    private final ConfigurationManager configurationManager;
    private volatile Settings settings;

    public SmtpAlertActionFactory(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
    }

    @Override
    public AlertAction createAction(XContentParser parser) throws IOException {
        String display = null;
        List<String> addresses = new ArrayList<>();

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                switch (currentFieldName) {
                    case "display":
                        display = parser.text();
                        break;
                    default:
                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                }
            } else if (token == XContentParser.Token.START_ARRAY) {
                switch (currentFieldName) {
                    case "addresses":
                        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                            addresses.add(parser.text());
                        }
                        break;
                    default:
                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                }
            } else {
                throw new ElasticsearchIllegalArgumentException("Unexpected token [" + token + "]");
            }
        }
        return new SmtpAlertAction(display, addresses.toArray(new String[addresses.size()]));
    }

    @Override
    public boolean doAction(AlertAction action, Alert alert, TriggerResult result) {
        if (!(action instanceof SmtpAlertAction)) {
            throw new ElasticsearchIllegalStateException("Bad action [" + action.getClass() + "] passed to EmailAlertActionFactory expected [" + SmtpAlertAction.class + "]");
        }
        SmtpAlertAction smtpAlertAction = (SmtpAlertAction)action;
        if (settings == null) {
            settings = configurationManager.getGlobalConfig();
            configurationManager.registerListener(this);
        }

        if (settings == null) {
            throw new ElasticsearchException("Unable to retrieve [" + GLOBAL_EMAIL_CONFIG + "] from the config index.");
        }


        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", settings.get(SERVER_SETTING, "smtp.gmail.com"));
        props.put("mail.smtp.port", settings.getAsInt(PORT_SETTING, 587));
        final Session session;
        if (settings.get(PASSWD_SETTING) != null) {
            session = Session.getInstance(props,
                    new javax.mail.Authenticator() {
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(settings.get(FROM_SETTING), settings.get(PASSWD_SETTING));
                        }
                    });
        } else {
            session = Session.getDefaultInstance(props);
        }

        Message message = new MimeMessage(session);
        try {
            message.setFrom(new InternetAddress(settings.get(FROM_SETTING)));
            message.setRecipients(Message.RecipientType.TO,
                    smtpAlertAction.getEmailAddresses().toArray(new Address[1]));

            message.setSubject("Elasticsearch Alert " + alert.getAlertName() + " triggered");

            StringBuilder output = new StringBuilder();
            output.append("The following query triggered because ").append(result.getTrigger().toString()).append("\n");
            Object totalHits = XContentMapValues.extractValue("hits.total", result.getTriggerResponse());
            output.append("The total number of hits returned : ").append(totalHits).append("\n");
            output.append("For query : ").append(result.getActionRequest());
            output.append("\n");
            output.append("Indices : ");
            for (String index : result.getActionRequest().indices()) {
                output.append(index);
                output.append("/");
            }
            output.append("\n");
            output.append("\n");


            if (smtpAlertAction.getDisplayField() != null) {
                List<Map<String, Object>> hits = (List<Map<String, Object>>) XContentMapValues.extractValue("hits.hits", result.getActionResponse());
                for (Map<String, Object> hit : hits) {
                    Map<String, Object> _source = (Map<String, Object>) hit.get("_source");
                    if (_source.containsKey(smtpAlertAction.getDisplayField())) {
                        output.append(_source.get(smtpAlertAction.getDisplayField()).toString());
                    } else {
                        output.append(_source);
                    }
                    output.append("\n");
                }
            } else {
                output.append(result.getActionResponse().toString());
            }

            message.setText(output.toString());
            Transport.send(message);
        } catch (Exception e){
            throw new ElasticsearchException("Failed to send mail", e);
        }
        return true;
    }


    @Override
    public void receiveConfigurationUpdate(Settings settings) {
        this.settings = settings;
    }
}
