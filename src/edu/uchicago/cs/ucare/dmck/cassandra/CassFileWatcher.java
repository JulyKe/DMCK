package edu.uchicago.cs.ucare.dmck.cassandra;

import java.util.HashMap;
import java.util.Properties;

import edu.uchicago.cs.ucare.dmck.event.Event;
import edu.uchicago.cs.ucare.dmck.server.FileWatcher;
import edu.uchicago.cs.ucare.dmck.server.ModelCheckingServerAbstract;

public class CassFileWatcher extends FileWatcher {

	public CassFileWatcher(String sPath, ModelCheckingServerAbstract dmck) {
		super(sPath, dmck);
	}

	@Override
	public synchronized void proceedEachFile(String filename, Properties ev) {
		if (filename.startsWith("cassPaxos-")) {
			int sender = Integer.parseInt(ev.getProperty("sender"));
			int recv = Integer.parseInt(ev.getProperty("recv"));
			String verb = ev.getProperty("verb");

			int clientRequest = -1;
			if (verb.equals("PAXOS_PREPARE") || verb.equals("PAXOS_PROPOSE") || verb.equals("PAXOS_COMMIT")) {
				clientRequest = sender;
			} else if (verb.equals("PAXOS_PREPARE_RESPONSE") || verb.equals("PAXOS_PROPOSE_RESPONSE")
					|| verb.equals("PAXOS_COMMIT_RESPONSE")) {
				clientRequest = recv;
			}

			HashMap<String, String> payload = new HashMap<String, String>();
			String payloadString = ev.getProperty("payload");
			if (payloadString.length() > 2) {
				payloadString = payloadString.substring(1, payloadString.length() - 1);
				for (String part : payloadString.split(", ")) {
					String[] keyValue = part.split("=");
					payload.put(keyValue[0], keyValue[1]);
				}
			}

			HashMap<String, String> usrval = new HashMap<String, String>();
			String usrvalString = ev.getProperty("usrval");
			if (usrvalString.length() > 2) {
				usrvalString = usrvalString.substring(1, usrvalString.length() - 1);
				for (String part : usrvalString.split(", ")) {
					String[] keyValue = part.split("=");
					usrval.put(keyValue[0], keyValue[1]);
				}
			}

			long eventId = Long.parseLong(ev.getProperty("eventId"));
			long hashId = commonHashId(eventId);

			Event event = new Event(hashId);
			event.addKeyValue(Event.FROM_ID, sender);
			event.addKeyValue(Event.TO_ID, recv);
			event.addKeyValue(Event.FILENAME, filename);
			event.addKeyValue("verb", verb);
			event.addKeyValue("payload", payload);
			event.addKeyValue("usrval", usrval);
			event.addKeyValue("clientRequest", clientRequest);
			event.setVectorClock(dmck.getVectorClock(sender, recv));

			LOG.debug("DMCK receives Cass Paxos event with hashId-" + hashId + " sender-" + sender + " recv-" + recv
					+ " verb-" + verb + " payload: " + payload.toString() + " usrval: " + usrval.toString()
					+ " clientRequest-" + clientRequest + " filename-" + filename);

			dmck.offerPacket(event);
		} else if (filename.startsWith("cassUpdate-")) {
			int sender = Integer.parseInt(ev.getProperty("sender"));
			String type = ev.getProperty("type");
			String ballot = ev.getProperty("ballot");
			int key = Integer.parseInt(ev.getProperty("key"));
			int value = Integer.parseInt(ev.getProperty("value"));

			LOG.debug("Update state node-" + sender + " type-" + type + " ballot-" + ballot + " key-" + key + " value-"
					+ value);

			dmck.localStates[sender].setKeyValue(type + "Ballot-" + key, ballot);
			dmck.localStates[sender].setKeyValue(type + "Value-" + key, value);
		} else if (filename.startsWith("cassResponseUpdate-")) {
			int id = Integer.parseInt(ev.getProperty("recv"));
			String type = ev.getProperty("type");
			int resp = Integer.parseInt(ev.getProperty("response"));

			LOG.debug("Update state node-" + id + " type-" + type + " response-" + resp);

			int currentResp = dmck.localStates[id].getValue(type) == null ? 0 + resp
					: (int) dmck.localStates[id].getValue(type) + resp;
			dmck.localStates[id].setKeyValue(type, currentResp);
		} else if (filename.startsWith("cassWorkloadUpdate-")) {
			int id = Integer.parseInt(ev.getProperty("id"));
			String isApplied = ev.getProperty("isApplied");

			dmck.isApplied.put(id, isApplied);

			LOG.debug("DMCK receives Cass Workload Accomplishment Update filename-" + filename);
		}

		removeProceedFile(filename);
	}

}
