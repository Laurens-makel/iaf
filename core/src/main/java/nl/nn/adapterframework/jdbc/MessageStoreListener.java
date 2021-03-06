/*
   Copyright 2015-2017 Nationale-Nederlanden, 2020, 2021 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.adapterframework.jdbc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.text.StringEscapeUtils;

import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.IMessageBrowser;
import nl.nn.adapterframework.core.ListenerException;
import nl.nn.adapterframework.core.ProcessState;
import nl.nn.adapterframework.doc.IbisDoc;
import nl.nn.adapterframework.receivers.MessageWrapper;
import nl.nn.adapterframework.receivers.Receiver;
import nl.nn.adapterframework.stream.Message;

/**
 * Read messages from the ibisstore previously stored by a
 * {@link MessageStoreSender}.
 * 
 * Example configuration:
 * <code><pre>
		&lt;listener
			name="MyListener"
			className="nl.nn.adapterframework.jdbc.MessageStoreListener"
			jmsRealm="jdbc"
			slotId="${instance.name}/ServiceName"
			sessionKeys="key1,key2"
		/>
		&lt;!-- On error the message is moved to the errorStorage. And when moveToMessageLog="true" also to the messageLog (after manual resend the messageLog doesn't change). -->
		&lt;errorStorage
			className="nl.nn.adapterframework.jdbc.JdbcTransactionalStorage"
			jmsRealm="jdbc"
			slotId="${instance.name}/ServiceName"
		/>
 * </pre></code>
 * 
 * 
 * @author Jaco de Groot
 */
public class MessageStoreListener extends JdbcTableListener {
	private String slotId;
	private String sessionKeys = null;
	private boolean moveToMessageLog = true;

	private List<String> sessionKeysList;

	{
		setTableName("IBISSTORE");
		setKeyField("MESSAGEKEY");
		setMessageField("MESSAGE");
		setMessageFieldType("blob");
		setBlobSmartGet(true);
		setStatusField("TYPE");
		setTimestampField("MESSAGEDATE");
		setStatusValueAvailable(IMessageBrowser.StorageType.MESSAGESTORAGE.getCode());
		setStatusValueProcessed(IMessageBrowser.StorageType.MESSAGELOG_RECEIVER.getCode());
		setStatusValueError(IMessageBrowser.StorageType.ERRORSTORAGE.getCode());
	}
	
	@Override
	public void configure() throws ConfigurationException {
		setSelectCondition("SLOTID = '" + slotId + "'");
		super.configure();
		if (sessionKeys != null) {
			sessionKeysList = new ArrayList<>();
			StringTokenizer stringTokenizer = new StringTokenizer(sessionKeys, ",");
			while (stringTokenizer.hasMoreElements()) {
				sessionKeysList.add(stringTokenizer.nextToken());
			}
		}
		if (isMoveToMessageLog()) {
			String setClause = "COMMENTS = '" + Receiver.RCV_MESSAGE_LOG_COMMENTS + "', EXPIRYDATE = "+getDbmsSupport().getDateAndOffset(getDbmsSupport().getSysDate(),30);
			setUpdateStatusQuery(ProcessState.DONE, createUpdateStatusQuery(getStatusValue(ProcessState.DONE),setClause));
		} else {
			String query = "DELETE FROM IBISSTORE WHERE MESSAGEKEY = ?";
			setUpdateStatusQuery(ProcessState.DONE, query);
			setUpdateStatusQuery(ProcessState.ERROR, query);
		}
	}

	@Override
	public Object getRawMessage(Map<String,Object> threadContext) throws ListenerException {
		Object rawMessage = super.getRawMessage(threadContext);
		if (rawMessage != null && sessionKeys != null) {
			MessageWrapper<?> messageWrapper = (MessageWrapper<?>)rawMessage;
			try {
				StringTokenizer strTokenizer = new StringTokenizer(messageWrapper.getMessage().asString(), ",");
				messageWrapper.setMessage(new Message(strTokenizer.nextToken()));
				int i = 0;
				while (strTokenizer.hasMoreTokens()) {
					threadContext.put(sessionKeysList.get(i), StringEscapeUtils.unescapeCsv(strTokenizer.nextToken()));
					i++;
				}
			} catch (IOException e) {
				throw new ListenerException("cannot convert message",e);
			}
		}
		return rawMessage;
	}

	protected IMessageBrowser<Object> augmentMessageBrowser(IMessageBrowser<Object> browser) {
		if (browser!=null && browser instanceof JdbcTableMessageBrowser) {
			JdbcTableMessageBrowser<Object> jtmb = (JdbcTableMessageBrowser<Object>)browser;
			jtmb.setCommentField("COMMENTS");
			jtmb.setExpiryDateField("EXPIRYDATE");
			jtmb.setHostField("HOST");
		}
		return browser;
	}
	
	@Override
	public IMessageBrowser<Object> getMessageBrowser(ProcessState state) {
		IMessageBrowser<Object> browser = super.getMessageBrowser(state);
		if (browser!=null) {
			return augmentMessageBrowser(browser);
		}
		return null;
	}


	@IbisDoc({"1", "Identifier for this service", ""})
	public void setSlotId(String slotId) {
		this.slotId = slotId;
	}
	public String getSlotId() {
		return slotId;
	}

	@IbisDoc({"2", "Comma separated list of sessionKey's to be read together with the message. Please note: corresponding {@link MessagestoreSender} must have the same value for this attribute", ""})
	public void setSessionKeys(String sessionKeys) {
		this.sessionKeys = sessionKeys;
	}
	public String getSessionKeys() {
		return sessionKeys;
	}

	@IbisDoc({"3", "Move to messageLog after processing, as the message is already stored in the ibisstore only some fields need to be updated. When set false, messages are deleted after being processed", "true"})
	public void setMoveToMessageLog(boolean moveToMessageLog) {
		this.moveToMessageLog = moveToMessageLog;
	}
	public boolean isMoveToMessageLog() {
		return moveToMessageLog;
	}

	@Override
	@IbisDoc({"4", "Value of status field indicating is being processed. Set to 'I' if database has no SKIP LOCKED functionality or the Receiver cannot be set to Required or RequiresNew.", ""})
	public void setStatusValueInProcess(String string) {
		super.setStatusValueInProcess(string);
	}

}
