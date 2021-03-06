package edu.uchicago.cs.ucare.dmck.transition;

import java.io.Serializable;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.uchicago.cs.ucare.dmck.server.ReductionAlgorithmsModelChecker;
import edu.uchicago.cs.ucare.dmck.util.LocalState;

public class AbstractGlobalStates implements Serializable {

	private static final long serialVersionUID = 6738349425843797880L;

	private static final Logger LOG = LoggerFactory.getLogger(AbstractGlobalStates.class);

	private LocalState[] abstractGlobalStateBefore;
	private LocalState[] abstractGlobalStateAfter;
	private LocalState executingNodeState;
	private Transition event;

	public AbstractGlobalStates(LocalState[] globalStates, Transition event) {
		abstractGlobalStateBefore = AbstractGlobalStates.getAbstractGlobalStates(globalStates);
		if (event instanceof PacketSendTransition) {
			int nodeId = ((PacketSendTransition) event).getPacket().getToId();
			executingNodeState = abstractGlobalStateBefore[nodeId];
			this.event = ((PacketSendTransition) event).clone();
		} else if (event instanceof AbstractNodeOperationTransition) {
			int nodeId = ((AbstractNodeOperationTransition) event).id;
			if (nodeId > -1) {
				this.event = ((AbstractNodeOperationTransition) event).getRealNodeOperationTransition(nodeId);
			} else {
				this.event = ((AbstractNodeOperationTransition) event).getRealNodeOperationTransition();
			}
			executingNodeState = abstractGlobalStateBefore[nodeId];
		} else if (event instanceof NodeCrashTransition) {
			int nodeId = ((NodeCrashTransition) event).id;
			executingNodeState = abstractGlobalStateBefore[nodeId];
			this.event = ((NodeCrashTransition) event).clone();
		} else if (event instanceof NodeStartTransition) {
			int nodeId = ((NodeStartTransition) event).id;
			executingNodeState = abstractGlobalStateBefore[nodeId];
			this.event = ((NodeStartTransition) event).clone();
		} else {
			LOG.error("Event=" + event.toString() + " cannot be abstracted yet. Event class=" + event.getClass());
		}
		abstractGlobalStateAfter = new LocalState[globalStates.length];
	}

	public void setAbstractGlobalStateAfter(LocalState[] globalStates) {
		abstractGlobalStateAfter = AbstractGlobalStates.getAbstractGlobalStates(globalStates);
	}

	public LocalState getExecutingNodeState() {
		return executingNodeState;
	}

	public Transition getEvent() {
		return event;
	}

	public LocalState[] getAbstractGlobalStateBefore() {
		return abstractGlobalStateBefore;
	}

	public boolean equals(AbstractGlobalStates otherAGS) {
		// check executing node state similarity
		if (executingNodeState.equals(otherAGS.getExecutingNodeState())) {
			return false;
		}

		// check per event type
		if (event instanceof PacketSendTransition && otherAGS.getEvent() instanceof PacketSendTransition) {
			PacketSendTransition msg1 = (PacketSendTransition) event;
			PacketSendTransition msg2 = (PacketSendTransition) otherAGS.getEvent();
			for (String eventKey : msg1.getPacket().getAllKeys()) {
				// if eventKey is part of nonAbstractEventKeys then skip checking
				boolean skip = false;
				for (String nonAbstractKey : ReductionAlgorithmsModelChecker.nonAbstractEventKeys) {
					if (eventKey.equals(nonAbstractKey)) {
						skip = true;
						break;
					}
				}
				if (skip) {
					continue;
				}

				// if msg2 does not have eventKey in msg1 then both messages are not equal
				if (msg2.getPacket().getValue(eventKey) == null) {
					return false;
				}

				// if values of the same key in both messages are different then both messages
				// are not equal
				if (!msg2.getPacket().getValue(eventKey).equals(msg1.getPacket().getValue(eventKey))) {
					return false;
				}
			}
		} else if (event instanceof NodeCrashTransition && otherAGS.getEvent() instanceof NodeCrashTransition) {
			// continue comparison
		} else if (event instanceof NodeStartTransition && otherAGS.getEvent() instanceof NodeStartTransition) {
			// continue comparison
		} else {
			return false;
		}

		return this.equals(otherAGS.getAbstractGlobalStateBefore());
	}

	public boolean equals(LocalState[] otherAGS) {
		// check before abstract global states similarity
		int numNode = abstractGlobalStateBefore.length;
		ArrayList<Integer> identical = new ArrayList<Integer>();
		for (int curNodeId = 0; curNodeId < numNode; curNodeId++) {
			boolean stateExist = false;
			for (int otherNodeId = 0; otherNodeId < numNode; otherNodeId++) {
				if (!identical.contains(otherNodeId)
						&& abstractGlobalStateBefore[curNodeId].toString().equals(otherAGS[otherNodeId].toString())) {
					stateExist = true;
					identical.add(otherNodeId);
					break;
				}
			}
			if (!stateExist) {
				return false;
			}
		}

		return true;
	}

	public AbstractEventConsequence getAbstractEventConsequence() {
		// to save nodeIDs that are found with similar state
		ArrayList<Integer> similar = new ArrayList<Integer>();
		for (int afterNodeId = 0; afterNodeId < abstractGlobalStateAfter.length; afterNodeId++) {
			boolean identicalState = false;
			for (int beforeNodeId = 0; beforeNodeId < abstractGlobalStateBefore.length; beforeNodeId++) {
				if (!similar.contains(afterNodeId) && abstractGlobalStateAfter[afterNodeId].toString()
						.equals(abstractGlobalStateBefore[beforeNodeId].toString())) {
					similar.add(afterNodeId);
					identicalState = true;
					break;
				}
			}
			if (!identicalState) {
				return new AbstractEventConsequence(executingNodeState, event, abstractGlobalStateAfter[afterNodeId]);
			}
		}
		return new AbstractEventConsequence(executingNodeState, event, executingNodeState);
	}

	public static LocalState[] getAbstractGlobalStates(LocalState[] globalStates) {
		LocalState[] ags = new LocalState[globalStates.length];
		// recreate the local state only based on abstract global state keys
		for (int i = 0; i < globalStates.length; i++) {
			LocalState newAbstractLocalState = new LocalState();
			for (String key : ReductionAlgorithmsModelChecker.abstractGlobalStateKeys) {
				newAbstractLocalState.setKeyValue(key, (Serializable) globalStates[i].getValue(key));
			}
			ags[i] = newAbstractLocalState;
		}
		return ags;
	}
}
