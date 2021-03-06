package edu.uchicago.cs.ucare.dmck.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import com.almworks.sqlite4java.SQLiteException;

import edu.uchicago.cs.ucare.dmck.transition.AbstractEventConsequence;
import edu.uchicago.cs.ucare.dmck.transition.AbstractGlobalStates;
import edu.uchicago.cs.ucare.dmck.transition.AbstractNodeCrashTransition;
import edu.uchicago.cs.ucare.dmck.transition.AbstractNodeOperationTransition;
import edu.uchicago.cs.ucare.dmck.transition.AbstractNodeStartTransition;
import edu.uchicago.cs.ucare.dmck.transition.NodeCrashTransition;
import edu.uchicago.cs.ucare.dmck.transition.NodeOperationTransition;
import edu.uchicago.cs.ucare.dmck.transition.NodeStartTransition;
import edu.uchicago.cs.ucare.dmck.transition.PacketSendTransition;
import edu.uchicago.cs.ucare.dmck.transition.SleepTransition;
import edu.uchicago.cs.ucare.dmck.transition.Transition;
import edu.uchicago.cs.ucare.dmck.transition.TransitionTuple;
import edu.uchicago.cs.ucare.dmck.util.ExploredBranchRecorder;
import edu.uchicago.cs.ucare.dmck.util.LocalState;
import edu.uchicago.cs.ucare.dmck.util.SqliteExploredBranchRecorder;
import edu.uchicago.cs.ucare.dmck.util.VectorClockUtil;
import edu.uchicago.cs.ucare.dmck.util.WorkloadDriver;

public abstract class ReductionAlgorithmsModelChecker extends ModelCheckingServerAbstract {

	ExploredBranchRecorder exploredBranchRecorder;

	public static String[] abstractGlobalStateKeys;
	public static String[] nonAbstractEventKeys;

	// high priority
	LinkedList<LinkedList<TransitionTuple>> importantInitialPaths;
	LinkedList<LinkedList<TransitionTuple>> currentImportantInitialPaths;
	// normal priority
	LinkedList<LinkedList<TransitionTuple>> initialPaths;
	LinkedList<LinkedList<TransitionTuple>> currentInitialPaths;
	// low priority
	LinkedList<LinkedList<TransitionTuple>> unnecessaryInitialPaths;
	LinkedList<LinkedList<TransitionTuple>> currentUnnecessaryInitialPaths;

	HashSet<String> finishedInitialPaths;
	HashSet<String> currentFinishedInitialPaths;
	HashSet<String> initialPathSecondAttempt;
	LinkedList<TransitionTuple> currentPath;
	LinkedList<TransitionTuple> currentExploringPath = new LinkedList<TransitionTuple>();

	// record all transition global states before and after
	LinkedList<AbstractGlobalStates> incompleteEventHistory;
	LinkedList<AbstractGlobalStates> eventHistory;
	LinkedList<AbstractEventConsequence> eventImpacts;
	int recordedEventImpacts;

	protected boolean isSAMC;
	protected boolean enableMsgWWDisjoint;
	protected boolean enableMsgAlwaysDis;
	protected boolean enableDiskRW;
	protected boolean enableParallelism;
	protected boolean enableCrash2NoImpact;
	protected boolean enableCrashRebootDis;
	protected boolean enableSymmetry;
	protected boolean enableSymmetryDoubleCheck;
	protected boolean enableCRSRSSRuntime;

	String stateDir;

	int numCrash;
	int numReboot;
	int currentCrash;
	int currentReboot;

	int globalState2;
	LinkedList<boolean[]> prevOnlineStatus;

	LinkedList<LocalState[]> prevLocalStates;

	// record policy effectiveness
	Hashtable<String, Integer> policyRecord;

	public ReductionAlgorithmsModelChecker(String dmckName, FileWatcher fileWatcher, int numNode, int numCrash,
			int numReboot, String globalStatePathDir, String packetRecordDir, String workingDir,
			WorkloadDriver workloadDriver, String ipcDir) {
		super(dmckName, fileWatcher, numNode, globalStatePathDir, workingDir, workloadDriver, ipcDir);
		importantInitialPaths = new LinkedList<LinkedList<TransitionTuple>>();
		currentImportantInitialPaths = new LinkedList<LinkedList<TransitionTuple>>();
		initialPaths = new LinkedList<LinkedList<TransitionTuple>>();
		currentInitialPaths = new LinkedList<LinkedList<TransitionTuple>>();
		unnecessaryInitialPaths = new LinkedList<LinkedList<TransitionTuple>>();
		currentUnnecessaryInitialPaths = new LinkedList<LinkedList<TransitionTuple>>();
		finishedInitialPaths = new HashSet<String>();
		currentFinishedInitialPaths = new HashSet<String>();
		initialPathSecondAttempt = new HashSet<String>();
		incompleteEventHistory = new LinkedList<AbstractGlobalStates>();
		eventHistory = new LinkedList<AbstractGlobalStates>();
		eventImpacts = new LinkedList<AbstractEventConsequence>();
		recordedEventImpacts = 0;
		this.numCrash = numCrash;
		this.numReboot = numReboot;
		isSAMC = true;
		enableMsgWWDisjoint = false;
		enableMsgAlwaysDis = false;
		enableDiskRW = false;
		enableParallelism = false;
		enableCrash2NoImpact = false;
		enableCrashRebootDis = false;
		enableSymmetry = false;
		enableCRSRSSRuntime = false;
		policyRecord = new Hashtable<String, Integer>();

		stateDir = packetRecordDir;
		try {
			exploredBranchRecorder = new SqliteExploredBranchRecorder(packetRecordDir);
		} catch (SQLiteException e) {
			LOG.error("", e);
		}

		getSAMCConfig();
		loadInitialPathFromFile();
		resetTest();
	}

	@Override
	public void resetTest() {
		if (exploredBranchRecorder == null) {
			return;
		}
		super.resetTest();
		currentCrash = 0;
		currentReboot = 0;
		modelChecking = new PathTraversalWorker();
		currentEnabledTransitions = new LinkedList<Transition>();
		currentExploringPath = new LinkedList<TransitionTuple>();
		exploredBranchRecorder.resetTraversal();
		prevOnlineStatus = new LinkedList<boolean[]>();
		File waiting = new File(stateDir + "/.waiting");
		try {
			waiting.createNewFile();
		} catch (IOException e) {
			LOG.error("", e);
		}
		prevLocalStates = new LinkedList<LocalState[]>();

		// record policy effectiveness
		storePolicyEffectiveness();
	}

	// load samc.conf to the DMCK
	public void getSAMCConfig() {
		if (!(this instanceof DporModelChecker)) {
			try {
				String samcConfigFile = workingDirPath + "/samc.conf";
				FileInputStream configInputStream = new FileInputStream(samcConfigFile);
				Properties samcConf = new Properties();
				samcConf.load(configInputStream);
				configInputStream.close();

				abstractGlobalStateKeys = samcConf.getProperty("abstract_global_state") != null
						? samcConf.getProperty("abstract_global_state").split(",")
						: null;
				nonAbstractEventKeys = samcConf.getProperty("non_abstract_event") != null
						? samcConf.getProperty("non_abstract_event").split(",")
						: null;
				this.enableMsgWWDisjoint = samcConf.getProperty("msg_ww_disjoint", "false").equals("true");
				this.enableMsgAlwaysDis = samcConf.getProperty("msg_always_dis", "false").equals("true");
				this.enableDiskRW = samcConf.getProperty("disk_rw", "false").equals("true");
				this.enableCrash2NoImpact = samcConf.getProperty("crash_2_noimpact", "false").equals("true");
				this.enableCrashRebootDis = samcConf.getProperty("crash_reboot_dis", "false").equals("true");
				this.enableParallelism = samcConf.getProperty("parallelism", "false").equals("true");
				this.enableSymmetry = samcConf.getProperty("symmetry", "false").equals("true");
				this.enableSymmetryDoubleCheck = samcConf.getProperty("symmetry_double_check", "false").equals("true");
				this.enableCRSRSSRuntime = samcConf.getProperty("crs_rss_runtime", "false").equals("true");

				// sanity check
				if (this.enableSymmetry && nonAbstractEventKeys == null) {
					LOG.error(
							"In samc.conf, you have configured symmetry=true, but you have not specified non_abstract_event.");
					System.exit(1);
				}
			} catch (Exception e) {
				LOG.error("Error in reading samc config file:\n" + e.toString());
			}
		}
	}

	// load existing list of paths to particular queue
	@SuppressWarnings("unchecked")
	protected void loadPaths(LinkedList<LinkedList<TransitionTuple>> pathQueue, int numRecord, String fileName) {
		for (int record = 1; record <= numRecord; record++) {
			File initialPathFile = new File(testRecordDirPath + "/" + record + "/" + fileName);
			if (initialPathFile.exists()) {
				ObjectInputStream ois;
				try {
					ois = new ObjectInputStream(new FileInputStream(initialPathFile));
					LinkedList<LinkedList<TransitionTuple>> streamedInitialPaths = (LinkedList<LinkedList<TransitionTuple>>) ois
							.readObject();
					for (LinkedList<TransitionTuple> dumbInitPath : streamedInitialPaths) {
						LinkedList<TransitionTuple> initPath = new LinkedList<TransitionTuple>();
						for (TransitionTuple dumbTuple : dumbInitPath) {
							initPath.add(TransitionTuple.getRealTransitionTuple(this, dumbTuple));
						}
						pathQueue.add(initPath);
					}
					ois.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	protected void loadEventIDsPaths(Collection<String> pathQueue, int numRecord, String fileName) {
		for (int i = 1; i <= numRecord; i++) {
			File listOfPathsFile = new File(testRecordDirPath + "/" + i + "/" + fileName);
			if (listOfPathsFile.exists()) {
				try {
					BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(listOfPathsFile)));
					String path;
					while ((path = br.readLine()) != null) {
						pathQueue.add(path);
					}
					br.close();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	@SuppressWarnings("unlikely-arg-type")
	protected void loadInitialPathFromFile() {
		try {
			// grab the max record directory
			File recordDir = new File(testRecordDirPath);
			File[] listOfRecordDir = recordDir.listFiles();
			int numRecord = listOfRecordDir.length;

			if (enableParallelism) {
				loadPaths(importantInitialPaths, numRecord, "importantInitialPathsInQueue");
				loadPaths(unnecessaryInitialPaths, numRecord, "unnecessaryInitialPathsInQueue");
			}

			loadPaths(initialPaths, numRecord, "initialPathsInQueue");

			for (int i = 1; i <= numRecord; i++) {
				File initialPathFile = new File(testRecordDirPath + "/" + i + "/currentInitialPath");
				if (initialPathFile.exists()) {
					BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(initialPathFile)));
					String path = null;
					while ((path = br.readLine()) != null) {
						if (enableParallelism) {
							importantInitialPaths.remove(path);
							unnecessaryInitialPaths.remove(path);
						} else {
							for (LinkedList<TransitionTuple> pathInQueue : initialPaths) {
								if (pathToString(pathInQueue).equals(path.trim())) {
									initialPaths.remove(pathInQueue);
									break;
								}
							}
						}
					}
					br.close();
				}
			}

			loadEventIDsPaths(finishedInitialPaths, numRecord, "finishedInitialPaths");

			LOG.info("Total Important Initial Path that has been loaded:" + importantInitialPaths.size());
			LOG.info("Total Initial Path that has been loaded:" + initialPaths.size());
			LOG.info("Total Unnecessary Initial Path that has been loaded:" + unnecessaryInitialPaths.size());

			loadNextInitialPath(false, false);

		} catch (FileNotFoundException e1) {
			LOG.warn("", e1);
		} catch (IOException e1) {
			LOG.warn("", e1);
		}
	}

	public void loadNextInitialPath(boolean markPrevPathFinished, boolean finishedExploredAll) {
		while (true) {
			if (importantInitialPaths.size() > 0) {
				currentPath = importantInitialPaths.remove();
			} else if (initialPaths.size() > 0) {
				currentPath = initialPaths.remove();
			} else if (unnecessaryInitialPaths.size() > 0) {
				currentPath = unnecessaryInitialPaths.remove();
			} else {
				if (markPrevPathFinished) {
					exploredBranchRecorder.resetTraversal();
					exploredBranchRecorder.markBelowSubtreeFinished();
				}
				if (finishedExploredAll) {
					hasFinishedAllExploration = true;
				}
			}
			
			if (this.enableSymmetryDoubleCheck && currentPath != null) {
				if (!isSymmetricPath(currentPath)) {
					break;
				}
				recordPolicyEffectiveness("symmetry_double_check");
				String tmp = "Reduce Initial Path due to Symmetry Double Check reduction algorithm:\n";
				for (TransitionTuple event : currentPath) {
					tmp += event.transition.toString() + "\n";
				}
				LOG.info(tmp);
				collectDebug(tmp);
			} else {
				break;
			}
		}
	}

	public Transition nextTransition(LinkedList<Transition> transitions) {
		// CRS runtime checking
		if (this.isSAMC && (this.numCrash > 0 || this.numReboot > 0)) {
			ListIterator<Transition> iter = transitions.listIterator();
			LinkedList<Transition> tempContainer = new LinkedList<Transition>();
			int queueSize = transitions.size();
			int continueCounter = 0;
			while (iter.hasNext()) {
				Transition transition = iter.next();
				if (this.enableCRSRSSRuntime) {
					int isSymmetric = isRuntimeCrashRebootSymmetric(transition);

					if (isSymmetric == -1 && continueCounter < queueSize) {
						LOG.debug("Abstract Event has been executed before in the history. transition="
								+ transition.toString());
						continueCounter++;
						tempContainer.add(transition);
						continue;
					} else if (isSymmetric >= 0) {
						AbstractNodeOperationTransition tmp = (AbstractNodeOperationTransition) transition;
						tmp.id = isSymmetric;
						transition = tmp;
						LOG.debug(
								"NextTransition is suggested to transform " + transition.toString() + " to " + tmp.id);
					}
				}
				if (continueCounter >= queueSize) {
					LOG.debug(
							"Queue only consists of uninteresting abstract event. DMCK decided to choose the first event.");
				}
				if (!exploredBranchRecorder.isSubtreeBelowChildFinished(transition.getTransitionId())) {
					iter.remove();
					return transition;
				}
			}
			if (tempContainer.size() > 0) {
				Transition result = tempContainer.getFirst();
				transitions.remove(result);
				return result;
			}
		} else if (transitions.size() > 0) {
			return transitions.removeFirst();
		}

		return null;
	}

	protected void adjustCrashAndReboot(LinkedList<Transition> enabledTransitions) {
		int numOnline = 0;
		for (int i = 0; i < numNode; ++i) {
			if (isNodeOnline(i)) {
				numOnline++;
			}
		}
		int numOffline = numNode - numOnline;
		int tmp = numOnline < numCrash - currentCrash ? numOnline : numCrash - currentCrash;
		for (int i = 0; i < tmp; ++i) {
			AbstractNodeCrashTransition crash = new AbstractNodeCrashTransition(this);
			for (int j = 0; j < numNode; ++j) {
				crash.setPossibleVectorClock(j, vectorClocks[j][numNode]);
			}
			enabledTransitions.add(crash);
			currentCrash++;
			numOffline++;
		}
		tmp = numOffline < numReboot - currentReboot ? numOffline : numReboot - currentReboot;
		for (int i = 0; i < tmp; ++i) {
			AbstractNodeStartTransition start = new AbstractNodeStartTransition(this);
			for (int j = 0; j < numNode; ++j) {
				start.setPossibleVectorClock(j, vectorClocks[j][numNode]);
			}
			enabledTransitions.add(start);
			currentReboot++;
		}
	}

	protected void markPacketsObsolete(int obsoleteBy, int crashingNode, LinkedList<Transition> enabledTransitions) {
		ListIterator<Transition> iter = enabledTransitions.listIterator();
		while (iter.hasNext()) {
			Transition t = iter.next();
			if (t instanceof PacketSendTransition) {
				PacketSendTransition p = (PacketSendTransition) t;
				if (p.getPacket().getFromId() == crashingNode || p.getPacket().getToId() == crashingNode) {
					p.getPacket().setObsolete(true);
					p.getPacket().setObsoleteBy(obsoleteBy);
				}
			}
		}
	}

	public void updateGlobalState2() {
		int prime = 31;
		globalState2 = getGlobalState();
		globalState2 = prime * globalState2 + currentEnabledTransitions.hashCode();
		for (int i = 0; i < numNode; ++i) {
			for (int j = 0; j < numNode; ++j) {
				globalState2 = prime * globalState2 + Arrays.hashCode(messagesQueues[i][j].toArray());
			}
		}
	}

	protected int getGlobalState2() {
		return globalState2;
	}

	protected void convertExecutedAbstractTransitionToReal(LinkedList<TransitionTuple> executedPath) {
		ListIterator<TransitionTuple> iter = executedPath.listIterator();
		while (iter.hasNext()) {
			TransitionTuple iterItem = iter.next();
			if (iterItem.transition instanceof AbstractNodeCrashTransition) {
				AbstractNodeCrashTransition crash = (AbstractNodeCrashTransition) iterItem.transition;
				NodeCrashTransition crashTransition = new NodeCrashTransition(ReductionAlgorithmsModelChecker.this,
						crash.id);
				crashTransition.setVectorClock(crash.getPossibleVectorClock(crash.getId()));
				iter.set(new TransitionTuple(iterItem.state, crashTransition));
			} else if (iterItem.transition instanceof AbstractNodeStartTransition) {
				AbstractNodeStartTransition start = (AbstractNodeStartTransition) iterItem.transition;
				NodeStartTransition startTransition = new NodeStartTransition(ReductionAlgorithmsModelChecker.this,
						start.id);
				startTransition.setVectorClock(start.getPossibleVectorClock(start.getId()));
				iter.set(new TransitionTuple(iterItem.state, startTransition));
			}
		}
	}

	protected String pathToString(LinkedList<TransitionTuple> initialPath) {
		String path = "";
		for (int i = 0; i < initialPath.size(); i++) {
			if (initialPath.get(i).transition instanceof PacketSendTransition) {
				if (i == 0) {
					path = String.valueOf(initialPath.get(i).transition.getTransitionId());
				} else {
					path += "," + String.valueOf(initialPath.get(i).transition.getTransitionId());
				}
			} else {
				if (i == 0) {
					path = ((NodeOperationTransition) initialPath.get(i).transition).toStringForFutureExecution();
				} else {
					path += ","
							+ ((NodeOperationTransition) initialPath.get(i).transition).toStringForFutureExecution();
				}
			}
		}
		return path;
	}

	protected String pathToHistoryString(LinkedList<TransitionTuple> initialPath) {
		String result = "";
		String[] path = new String[numNode];
		for (TransitionTuple tuple : initialPath) {
			if (tuple.transition instanceof PacketSendTransition) {
				PacketSendTransition t = (PacketSendTransition) tuple.transition;
				if (path[t.getPacket().getToId()] == null) {
					path[t.getPacket().getToId()] = String.valueOf(t.getTransitionId());
				} else {
					path[t.getPacket().getToId()] += "," + String.valueOf(t.getTransitionId());
				}
			} else if (tuple.transition instanceof NodeOperationTransition) {
				NodeOperationTransition t = (NodeOperationTransition) tuple.transition;
				if (path[t.getId()] == null) {
					path[t.getId()] = String.valueOf(t.getTransitionId());
				} else {
					path[t.getId()] += "," + String.valueOf(t.getTransitionId());
				}
			}
		}
		for (int i = 0; i < path.length; i++) {
			result += i + ":" + path[i] + ";";
		}
		return result;
	}

	protected void addPathToFinishedInitialPath(LinkedList<TransitionTuple> path) {
		String newHistoryPath = pathToString(path);
		currentFinishedInitialPaths.add(newHistoryPath);
	}

	protected boolean isIdenticalHistoricalPath(String currentPath, String historicalPathNodes) {
		String[] currentPathNodes = currentPath.split(";");
		String[] historyStates = historicalPathNodes.split(";");
		for (int i = 0; i < currentPathNodes.length; i++) {
			if (!historyStates[i].startsWith(currentPathNodes[i])) {
				return false;
			}
		}
		return true;
	}

	protected boolean pathExistInHistory(LinkedList<TransitionTuple> path) {
		String currentPath = pathToString(path);
		for (String finishedPath : currentFinishedInitialPaths) {
			if (isIdenticalHistoricalPath(currentPath, finishedPath)) {
				return true;
			}
		}
		for (String finishedPath : finishedInitialPaths) {
			if (isIdenticalHistoricalPath(currentPath, finishedPath)) {
				return true;
			}
		}
		return false;
	}

	protected void addToInitialPathList(LinkedList<TransitionTuple> initialPath) {
		convertExecutedAbstractTransitionToReal(initialPath);
		if (!pathExistInHistory(initialPath)) {
			initialPaths.add(initialPath);
			addPathToFinishedInitialPath(initialPath);
		}
	}

	public static String getAbstractLocalState(LocalState ls) {
		String result = "[";
		boolean isFirst = true;
		for (String key : abstractGlobalStateKeys) {
			if (isFirst) {
				isFirst = false;
			} else {
				result += ", ";
			}
			result += key + "=" + ls.getValue(key);
		}
		result += "]";
		return result;
	}

	public static String getAbstractEvent(Transition ev) {
		String result = "";
		if (ev instanceof PacketSendTransition) {
			result = "abstract-message: ";
			PacketSendTransition msg = (PacketSendTransition) ev;
			boolean isFirst = true;
			for (String key : msg.getPacket().getAllKeys()) {
				boolean nextKey = false;
				for (String nonAbstractKey : ReductionAlgorithmsModelChecker.nonAbstractEventKeys) {
					if (key.equals(nonAbstractKey)) {
						nextKey = true;
						break;
					}
				}
				if (nextKey) {
					continue;
				}

				if (isFirst) {
					isFirst = false;
				} else {
					result += ", ";
				}
				result += key + "=" + msg.getPacket().getValue(key);
			}
		} else if (ev instanceof AbstractNodeCrashTransition || ev instanceof NodeCrashTransition) {
			result = "abstract-crash";
		} else if (ev instanceof AbstractNodeStartTransition || ev instanceof NodeStartTransition) {
			result = "abstract-reboot";
		}
		return result;
	}

	public static boolean isIdenticalAbstractLocalStates(LocalState ls1, LocalState ls2) {
		for (String key : abstractGlobalStateKeys) {
			if (ls1.getValue(key) == null && ls2.getValue(key) == null) {
				continue;
			} else if (ls1.getValue(key) == null || ls2.getValue(key) == null) {
				return false;
			} else if (!ls1.getValue(key).toString().equals(ls2.getValue(key).toString())) {
				return false;
			}
		}
		return true;
	}

	public static boolean isIdenticalAbstractEvent(Transition e1, Transition e2) {
		if (e1 instanceof PacketSendTransition && e2 instanceof PacketSendTransition) {
			PacketSendTransition m1 = (PacketSendTransition) e1;
			PacketSendTransition m2 = (PacketSendTransition) e2;
			for (String key : m1.getPacket().getAllKeys()) {
				// if key is in non abstract event keys, then it does not need to be evaluated
				boolean nextKey = false;
				for (String nonAbstractKey : ReductionAlgorithmsModelChecker.nonAbstractEventKeys) {
					if (key.equals(nonAbstractKey)) {
						nextKey = true;
						break;
					}
				}
				if (nextKey) {
					continue;
				}

				// if key in m1 does not exist in m2, these messages are not identical
				if (m2.getPacket().getValue(key) == null) {
					return false;
				}

				// if value in m1 and m2 are different, then these messages are not identical
				if (!m1.getPacket().getValue(key).toString().equals(m2.getPacket().getValue(key).toString())) {
					return false;
				}
			}
			return true;
		} else if ((e1 instanceof NodeCrashTransition || e1 instanceof AbstractNodeCrashTransition)
				&& (e2 instanceof NodeCrashTransition || e2 instanceof AbstractNodeCrashTransition)) {
			return true;
		} else if ((e1 instanceof NodeStartTransition || e1 instanceof AbstractNodeStartTransition)
				&& (e2 instanceof NodeStartTransition || e2 instanceof AbstractNodeStartTransition)) {
			return true;
		} else {
			return false;
		}
	}

	// focus on swapping the newTransition before oldTransition
	protected LinkedList<TransitionTuple> reorderEvents(LocalState[] wasLocalStates,
			LinkedList<TransitionTuple> initialPath, TransitionTuple oldTransition, TransitionTuple newTransition) {
		LinkedList<TransitionTuple> reorderingEvents = new LinkedList<TransitionTuple>();

		// compare initial path with dependency path that includes initial path
		if (newTransition.transition instanceof PacketSendTransition) {
			List<Transition> allBeforeTransitions = dependencies.get(newTransition.transition);
			ListIterator<Transition> beforeIter = allBeforeTransitions.listIterator();
			Iterator<TransitionTuple> checkingIter = initialPath.iterator();
			while (beforeIter.hasNext()) {
				Transition beforeTransition = beforeIter.next();
				boolean isFound = false;
				while (checkingIter.hasNext()) {
					if (checkingIter.next().transition.equals(beforeTransition)) {
						isFound = true;
						break;
					}
				}
				if (!isFound) {
					beforeIter.previous();
					break;
				}
			}
			while (beforeIter.hasNext()) {
				Transition nextEvent = beforeIter.next();
				reorderingEvents.add(new TransitionTuple(0, nextEvent));
			}
		}

		reorderingEvents.add(newTransition);
		reorderingEvents.add(new TransitionTuple(0, oldTransition.transition));

		return reorderingEvents;
	}

	@SuppressWarnings("unchecked")
	protected boolean addNewInitialPath(LocalState[] wasLocalStates, LinkedList<TransitionTuple> initialPath,
			TransitionTuple oldTransition, TransitionTuple newTransition) {
		// mark the initial path plus the old event as explored
		LinkedList<TransitionTuple> oldPath = (LinkedList<TransitionTuple>) initialPath.clone();
		convertExecutedAbstractTransitionToReal(oldPath);
		oldPath.add(new TransitionTuple(0, oldTransition.transition));
		addPathToFinishedInitialPath(oldPath);

		LinkedList<TransitionTuple> newInitialPath = (LinkedList<TransitionTuple>) initialPath.clone();
		convertExecutedAbstractTransitionToReal(newInitialPath);

		LinkedList<TransitionTuple> reorderedEvents = reorderEvents(wasLocalStates, newInitialPath, oldTransition,
				newTransition);

		newInitialPath.addAll(reorderedEvents);

		// check symmetrical path
		if (isSymmetricPath(newInitialPath)) {
			return false;
		}

		if (!pathExistInHistory(newInitialPath)) {
			LOG.info("Transition " + newTransition.transition + " is dependent with " + oldTransition.transition
					+ " at state " + oldTransition.state + " " + newInitialPath.hashCode());
			initialPaths.add(newInitialPath);
			if (!this.enableParallelism) {
				addPathToFinishedInitialPath(newInitialPath);
			}

			// add new initial path in debug.log
			String debugNewPath = "New Initial Path:\n";
			for (TransitionTuple t : newInitialPath) {
				debugNewPath += t.toString() + "\n";
			}
			collectDebug(debugNewPath);

			return true;
		}
		return false;
	}

	// save collection of paths to file
	protected boolean savePaths(LinkedList<LinkedList<TransitionTuple>> pathsQueue, String fileName) {
		if (pathsQueue.size() > 0) {
			try {
				ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(idRecordDirPath + "/" + fileName));
				LinkedList<LinkedList<TransitionTuple>> initialPathsList = new LinkedList<LinkedList<TransitionTuple>>();
				for (LinkedList<TransitionTuple> initPath : pathsQueue) {
					LinkedList<TransitionTuple> dumbPath = new LinkedList<TransitionTuple>();
					for (TransitionTuple realTuple : initPath) {
						dumbPath.add(realTuple.getSerializable(numNode));
					}
					initialPathsList.add(dumbPath);
				}
				oos.writeObject(initialPathsList);
				oos.close();

				return true;
			} catch (Exception e) {
				LOG.error("", e);
			}
		}
		return false;
	}

	protected boolean saveEventIDsPaths(Collection<String> pathsQueue, String fileName) {
		if (pathsQueue.size() > 0) {
			try {
				BufferedWriter bw = new BufferedWriter(
						new OutputStreamWriter(new FileOutputStream(new File(idRecordDirPath + "/" + fileName))));
				String paths = "";
				for (String path : pathsQueue) {
					paths += path + "\n";
				}
				bw.write(paths);
				bw.close();

				return true;
			} catch (FileNotFoundException e) {
				LOG.error("", e);
			} catch (IOException e) {
				LOG.error("", e);
			}
		}
		return false;
	}

	// to log paths
	protected void printPaths(String pathsName, Collection<String> paths) {
		String logs = pathsName + " consists of " + paths.size() + " paths:\n";
		int i = 1;
		for (String path : paths) {
			logs += "Path " + i++ + ":\n" + path + "\n";
		}
		LOG.info(logs);
	}

	protected void printPaths(String pathsName, LinkedList<LinkedList<TransitionTuple>> paths) {
		String logs = pathsName + " consists of " + paths.size() + " paths:\n";
		int i = 1;
		for (LinkedList<TransitionTuple> path : paths) {
			logs += "Path " + i++ + ":\n";
			for (TransitionTuple tuple : path) {
				logs += tuple.toString() + "\n";
			}
		}
		LOG.info(logs);
	}

	protected void saveInitialPaths() {
		try {
			if (currentPath != null) {
				BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
						new FileOutputStream(new File(idRecordDirPath + "/currentInitialPath"))));
				bw.write(pathToString(currentPath));
				bw.close();
			}

			if (this.enableParallelism) {
				// to save high priority initial path
				if (savePaths(currentImportantInitialPaths, "importantInitialPathsInQueue")) {
					importantInitialPaths.addAll(currentImportantInitialPaths);
					currentImportantInitialPaths.clear();
				}
			}

			// to save normal priority initial path
			if (savePaths(currentInitialPaths, "initialPathsInQueue")) {
				initialPaths.addAll(currentInitialPaths);
				currentInitialPaths.clear();

				printPaths("Initial Paths", initialPaths);
			}

			if (this.enableParallelism) {
				// to save low priority initial path
				if (savePaths(currentUnnecessaryInitialPaths, "unnecessaryInitialPathsInQueue")) {
					unnecessaryInitialPaths.addAll(currentUnnecessaryInitialPaths);
					currentUnnecessaryInitialPaths.clear();
				}

				printPaths("Important Initial Paths", importantInitialPaths);
				printPaths("Low Priority Initial Paths", unnecessaryInitialPaths);
			}

			if (saveEventIDsPaths(currentFinishedInitialPaths, "finishedInitialPaths")) {
				finishedInitialPaths.addAll(currentFinishedInitialPaths);
				currentFinishedInitialPaths.clear();
			}
		} catch (FileNotFoundException e) {
			LOG.error("", e);
		} catch (IOException e) {
			LOG.error("", e);
		}
	}

	protected void evaluateParallelismInitialPaths() {
		boolean evaluateAllInitialPaths = true;
		LinkedList<ParallelPath> transformedInitialPaths = new LinkedList<ParallelPath>();
		for (LinkedList<TransitionTuple> path : initialPaths) {
			ParallelPath newPath = new ParallelPath(path, dependencies);
			transformedInitialPaths.add(newPath);
		}

		// compare reordered path
		while (evaluateAllInitialPaths) {
			evaluateAllInitialPaths = false;
			LinkedList<ParallelPath> pathQueue = new LinkedList<ParallelPath>();
			pathQueue.addAll(transformedInitialPaths);

			for (int i = 0; i < pathQueue.size() - 1; i++) {
				for (int j = i + 1; j < pathQueue.size(); j++) {
					LOG.debug("Evaluate path-" + i + " vs path-" + j);
					ParallelPath path1 = pathQueue.get(i);
					ParallelPath path2 = pathQueue.get(j);
					ParallelPath newPath = path1.combineOtherPath(path2);
					if (newPath != null) {
						String combinedComment = "Combine ";
						evaluateAllInitialPaths = true;

						combinedComment += "path-" + j + " into ";
						transformedInitialPaths.remove(j);
						currentUnnecessaryInitialPaths.add(path2.getPath());
						addPathToFinishedInitialPath(path2.getPath());

						combinedComment += "path-" + i + "\n";
						transformedInitialPaths.remove(i);
						currentUnnecessaryInitialPaths.add(path1.getPath());
						addPathToFinishedInitialPath(path1.getPath());

						transformedInitialPaths.addFirst(newPath);
						recordPolicyEffectiveness("parallelism");

						LOG.debug(combinedComment);
						collectDebug(combinedComment);
						break;
					}
				}
				if (evaluateAllInitialPaths) {
					break;
				}
			}
		}

		for (ParallelPath newPath : transformedInitialPaths) {
			if (!pathExistInHistory(newPath.getPath())) {
				currentImportantInitialPaths.add(newPath.getPath());
				addPathToFinishedInitialPath(newPath.getPath());

				// add new initial path in debug.log
				String debugNewPath = "New Important Paths:\n";
				for (TransitionTuple t : newPath.getPath()) {
					debugNewPath += t.toString() + "\n";
				}
				collectDebug(debugNewPath);
			}
		}
		initialPaths.clear();
	}

	protected void findInitialPaths() {
		calculateInitialPaths();
		saveEventConsequences();

		printPaths("Initial Paths", initialPaths);

		if (this.enableParallelism) {
			evaluateParallelismInitialPaths();
		}

		saveInitialPaths();
	}

	Hashtable<Transition, List<Transition>> dependencies = new Hashtable<Transition, List<Transition>>();

	protected void calculateDependencyGraph() {
		dependencies.clear();
		List<TransitionTuple> realExecutionPath = new LinkedList<TransitionTuple>();
		for (TransitionTuple tuple : currentExploringPath) {
			if (tuple.transition instanceof AbstractNodeCrashTransition) {
				AbstractNodeCrashTransition abstractCrash = (AbstractNodeCrashTransition) tuple.transition;
				NodeCrashTransition realCrash = new NodeCrashTransition(this, abstractCrash.id);
				realCrash.setVectorClock(abstractCrash.getPossibleVectorClock(abstractCrash.id));
				TransitionTuple realTuple = new TransitionTuple(tuple.state, realCrash);
				realExecutionPath.add(realTuple);
			} else if (tuple.transition instanceof AbstractNodeStartTransition) {
				AbstractNodeStartTransition abstractStart = (AbstractNodeStartTransition) tuple.transition;
				NodeStartTransition realStart = new NodeStartTransition(this, abstractStart.id);
				realStart.setVectorClock(abstractStart.getPossibleVectorClock(abstractStart.id));
				TransitionTuple realTuple = new TransitionTuple(tuple.state, realStart);
				realExecutionPath.add(realTuple);
			} else {
				realExecutionPath.add(tuple);
			}
		}
		ListIterator<TransitionTuple> currentIter = realExecutionPath.listIterator(realExecutionPath.size());
		while (currentIter.hasPrevious()) {
			TransitionTuple current = currentIter.previous();
			LinkedList<Transition> partialOrder = new LinkedList<Transition>();
			if (currentIter.hasPrevious()) {
				ListIterator<TransitionTuple> comparingIter = realExecutionPath.listIterator(currentIter.nextIndex());
				while (comparingIter.hasPrevious()) {
					TransitionTuple comparing = comparingIter.previous();
					int compareResult = VectorClockUtil.isConcurrent(current.transition.getVectorClock(),
							comparing.transition.getVectorClock());
					if (compareResult == 1) {
						// hack solution for multiple client requests for
						// Cassandra system
						if (dmckName.equals("cassChecker") && current.transition instanceof PacketSendTransition
								&& comparing.transition instanceof PacketSendTransition) {
							PacketSendTransition lt = (PacketSendTransition) current.transition;
							PacketSendTransition tt = (PacketSendTransition) comparing.transition;
							int lastCR1 = (int) lt.getPacket().getValue("clientRequest");
							int lastCR2 = (int) tt.getPacket().getValue("clientRequest");
							if (lastCR1 != lastCR2) {
								continue;
							}
						}
						partialOrder.addFirst(comparing.transition);
					}
				}
			}
			dependencies.put(current.transition, partialOrder);
		}
	}

	protected int findTransition(String transitionId) {
		int result = -1;
		long transId = Long.parseLong(transitionId);
		for (int index = 0; index < currentEnabledTransitions.size(); index++) {
			if (transId == currentEnabledTransitions.get(index).getTransitionId()) {
				result = index;
				break;
			}
		}
		return result;
	}

	protected void recordPolicyEffectiveness(String policy) {
		if (policyRecord.containsKey(policy)) {
			int currentRecord = (Integer) policyRecord.get(policy);
			currentRecord += 1;
			policyRecord.put(policy, currentRecord);
		} else {
			policyRecord.put(policy, 1);
		}
	}

	protected void storePolicyEffectiveness() {
		try {
			BufferedWriter bw = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream(new File(workingDirPath + "/policyEffect.txt"))));

			String policyEffect = "";
			Enumeration<String> keys = policyRecord.keys();
			while (keys.hasMoreElements()) {
				String key = (String) keys.nextElement();
				policyEffect += key + ":" + policyRecord.get(key) + "\n";
			}

			bw.write(policyEffect);
			bw.close();
		} catch (Exception ex) {
			LOG.error(ex.toString());
		}
	}

	protected void saveEventConsequences() {
		if (testId > 0) {
			try {
				BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
						new FileOutputStream(new File(idRecordDirPath + "/" + "eventConsequences"))));
				String eventConsequences = "";
				for (int i = recordedEventImpacts; i < eventImpacts.size(); i++) {
					eventConsequences += eventImpacts.get(i) + "\n";
				}
				bw.write(eventConsequences);
				bw.close();

				recordedEventImpacts = eventImpacts.size();
			} catch (FileNotFoundException e) {
				LOG.error("", e);
			} catch (IOException e) {
				LOG.error("", e);
			}
		}

	}

	public boolean isSymmetricPath(LinkedList<TransitionTuple> initialPath) {
		if (this.enableSymmetry) {
			LocalState[] globalStates = getInitialGlobalStates();
			for (TransitionTuple event : initialPath) {
				AbstractGlobalStates ags = new AbstractGlobalStates(globalStates, event.transition);
				for (int nodeId = 0; nodeId < numNode; nodeId++) {
					if (isIdenticalAbstractLocalStates(globalStates[nodeId], ags.getExecutingNodeState())) {
						LocalState newLS = findLocalStateChange(globalStates[nodeId], ags.getEvent());
						if (newLS != null) {
							globalStates[nodeId] = newLS.clone();
						} else {
							LOG.debug("SYMMETRY CHECK: node with state=" + globalStates[nodeId].toString() + 
									" executes " + ags.getEvent().toString() + " transform to unknown state");
							return false;
						}
						break;
					}
				}
			}
			// if until the end of all initial path the DMCK can predict the end global
			// states
			// then the initial path is symmetrical
			recordPolicyEffectiveness("symmetry");
			return true;
		}
		return false;
	}

	public boolean isSymmetric(LocalState[] localStates, Transition event) {
		AbstractGlobalStates crashGS = new AbstractGlobalStates(localStates, event);
		boolean isSymmetric = false;
		for (AbstractGlobalStates historicalAGS : incompleteEventHistory) {
			if (historicalAGS.equals(crashGS)) {
				isSymmetric = true;
				break;
			}
		}
		return isSymmetric;
	}

	public boolean addEventToIncompleteHistory(LocalState[] localStates, Transition event) {
		// do not add null event to history
		if (event == null) {
			return false;
		}

		AbstractGlobalStates ags = new AbstractGlobalStates(localStates, event);
		if (isSymmetricEvent(ags) == -1) {
			incompleteEventHistory.add(ags);
			return true;
		}
		return false;
	}

	public void moveIncompleteEventToEventHistory(LocalState[] oldLocalStates, LocalState[] newLocalStates,
			Transition event) {
		AbstractGlobalStates ags = new AbstractGlobalStates(oldLocalStates, event);
		int eventIndex = -1;
		for (int x = 0; x < incompleteEventHistory.size(); x++) {
			if (incompleteEventHistory.get(x).equals(ags)) {
				eventIndex = x;
				break;
			}
		}
		if (eventIndex >= 0) {
			incompleteEventHistory.remove(eventIndex);
			ags.setAbstractGlobalStateAfter(newLocalStates);
			// collect : stateBefore >> event >> stateAfter chains
			AbstractEventConsequence newAEC = ags.getAbstractEventConsequence();
			boolean record = true;
			for (AbstractEventConsequence recordedAEC : eventImpacts) {
				if (recordedAEC.isIdentical(newAEC)) {
					record = false;
					break;
				}
			}
			if (record) {
				eventImpacts.add(newAEC);
			}
			eventHistory.add(ags);
		}
	}

	// if symmetric, return id in history. otherwise, return -1.
	public int isSymmetricEvent(AbstractGlobalStates otherAGS) {
		for (int x = 0; x < eventHistory.size(); x++) {
			if (eventHistory.get(x).equals(otherAGS)) {
				return x;
			}
		}
		return -1;
	}

	public LocalState findLocalStateChange(LocalState oldState, Transition event) {
		for (AbstractEventConsequence aec : eventImpacts) {
			LocalState newLS = aec.getTransformationState(oldState, event);
			if (newLS != null) {
				return newLS;
			}
		}
		return null;
	}

	protected abstract void calculateInitialPaths();

	protected abstract int isRuntimeCrashRebootSymmetric(Transition nextTransition);

	protected Transition transformStringToTransition(LinkedList<Transition> currentEnabledTransitions,
			String nextEvent) {
		Transition t = null;
		if (nextEvent.startsWith("nodecrash")) {
			int id = Integer.parseInt(nextEvent.substring(13).trim());
			NodeCrashTransition crashTransition = new NodeCrashTransition(ReductionAlgorithmsModelChecker.this, id);
			boolean absExist = false;
			for (Transition tt : currentEnabledTransitions) {
				if (tt instanceof AbstractNodeCrashTransition) {
					absExist = true;
					crashTransition.setVectorClock(((AbstractNodeCrashTransition) tt).getPossibleVectorClock(id));
					currentEnabledTransitions.remove(tt);
					break;
				}
			}
			if (absExist)
				t = crashTransition;
		} else if (nextEvent.startsWith("nodestart")) {
			int id = Integer.parseInt(nextEvent.substring(13).trim());
			NodeStartTransition startTransition = new NodeStartTransition(this, id);
			boolean absExist = false;
			for (Transition tt : currentEnabledTransitions) {
				if (tt instanceof AbstractNodeStartTransition) {
					absExist = true;
					startTransition.setVectorClock(((AbstractNodeStartTransition) tt).getPossibleVectorClock(id));
					currentEnabledTransitions.remove(tt);
					break;
				}
			}
			if (absExist)
				t = startTransition;
		} else if (nextEvent.startsWith("sleep")) {
			int time = Integer.parseInt(nextEvent.substring(6));
			t = new SleepTransition(time);
		} else {
			int index = findTransition(nextEvent);
			if (index >= 0) {
				t = currentEnabledTransitions.remove(index);
			}
		}
		return t;
	}

	protected Transition retrieveEventFromQueue(LinkedList<Transition> dmckQueue, Transition event) {
		if (event instanceof SleepTransition) {
			return new SleepTransition(((SleepTransition) event).getSleepTime());
		}
		for (int index = 0; index < dmckQueue.size(); index++) {
			if (event instanceof PacketSendTransition && dmckQueue.get(index) instanceof PacketSendTransition
					&& event.getTransitionId() == dmckQueue.get(index).getTransitionId()) {
				return dmckQueue.remove(index);
			} else if (event instanceof NodeCrashTransition
					&& dmckQueue.get(index) instanceof AbstractNodeCrashTransition) {
				AbstractNodeCrashTransition absCrashEvent = (AbstractNodeCrashTransition) dmckQueue.remove(index);
				int crashId = ((NodeCrashTransition) event).getId();
				NodeCrashTransition crashEvent = new NodeCrashTransition(this, crashId);
				crashEvent.setVectorClock(absCrashEvent.getPossibleVectorClock(crashId));
				return crashEvent;
			} else if (event instanceof NodeStartTransition
					&& dmckQueue.get(index) instanceof AbstractNodeStartTransition) {
				AbstractNodeStartTransition absRebootEvent = (AbstractNodeStartTransition) dmckQueue.remove(index);
				int rebootId = ((NodeStartTransition) event).getId();
				NodeStartTransition rebootEvent = new NodeStartTransition(this, rebootId);
				rebootEvent.setVectorClock(absRebootEvent.getPossibleVectorClock(rebootId));
				return rebootEvent;
			}
		}
		return null;
	}

	class PathTraversalWorker extends Thread {

		@SuppressWarnings("unchecked")
		@Override
		public void run() {
			int workloadRetry = 10;
			if (currentPath != null && !currentPath.isEmpty()) {
				LOG.info("Start with existing initial path first.");
				String tmp = "Current Initial Path:\n";
				for (TransitionTuple event : currentPath) {
					tmp += event.transition.toString() + "\n";
				}
				LOG.info(tmp);
				collectDebug(tmp);
				int tupleCounter = 0;
				for (TransitionTuple event : currentPath) {
					tupleCounter++;
					executeMidWorkload();
					updateSAMCQueue();
					updateGlobalState2();
					Transition nextEvent = retrieveEventFromQueue(currentEnabledTransitions, event.transition);
					for (int i = 0; i < 20; ++i) {
						if (nextEvent != null) {
							break;
						} else {
							try {
								Thread.sleep(steadyStateTimeout / 2);
								updateSAMCQueue();
							} catch (InterruptedException e) {
								LOG.error("", e);
							}
						}
					}
					if (nextEvent == null) {
						LOG.error("ERROR: Expected to execute " + event + ", but the event is not in queue.");
						LOG.error("Being in wrong state, there is not transition " + event + " to apply");
						try {
							pathRecordFile
									.write(("no transition. looking for event with id=" + event + "\n").getBytes());
						} catch (IOException e) {
							LOG.error("", e);
						}
						if (!initialPathSecondAttempt.contains(pathToString(currentPath))) {
							currentUnnecessaryInitialPaths.addFirst(currentPath);
							LOG.warn(
									"Try this initial path once again, but at low priority. Total Low Priority Initial Path="
											+ (unnecessaryInitialPaths.size() + currentUnnecessaryInitialPaths.size())
											+ " Total Initial Path Second Attempt=" + initialPathSecondAttempt.size());
							initialPathSecondAttempt.add(pathToString(currentPath));
						}
						loadNextInitialPath(true, false);
						LOG.warn("---- Quit of Path Execution because an error ----");
						resetTest();
						return;
					} else {
						executeEvent(nextEvent, tupleCounter <= directedInitialPath.size());
					}
				}
			}
			LOG.info("Try to find new path/Continue from Initial Path");
			boolean hasWaited = waitEndExploration == 0;
			while (true) {
				executeMidWorkload();
				updateSAMCQueue();
				updateGlobalState2();
				boolean terminationPoint = checkTerminationPoint(currentEnabledTransitions);
				if (terminationPoint && hasWaited) {
					boolean verifiedResult = verifier.verify();
					String detail = verifier.verificationDetail();
					saveResult(verifiedResult + " ; " + detail + "\n");
					exploredBranchRecorder.markBelowSubtreeFinished();
					calculateDependencyGraph();
					String currentFinishedPath = "Finished execution path\n";
					for (TransitionTuple tuple : currentExploringPath) {
						currentFinishedPath += tuple.toString() + "\n";
					}
					LOG.info(currentFinishedPath);
					LinkedList<TransitionTuple> finishedExploringPath = (LinkedList<TransitionTuple>) currentExploringPath
							.clone();
					convertExecutedAbstractTransitionToReal(finishedExploringPath);
					addPathToFinishedInitialPath(finishedExploringPath);
					findInitialPaths();
					loadNextInitialPath(true, true);
					LOG.info("---- End of Path Execution ----");
					resetTest();
					break;
				} else if (terminationPoint) {
					try {
						if (dmckName.equals("raftModelChecker") && waitForNextLE
								&& waitedForNextLEInDiffTermCounter < 20) {
							Thread.sleep(leaderElectionTimeout);
						} else {
							hasWaited = true;
							LOG.debug("Wait for any long process");
							Thread.sleep(waitEndExploration);
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					continue;
				}
				hasWaited = waitEndExploration == 0;
				Transition nextEvent;
				boolean isDirectedEvent = false;
				if (hasDirectedInitialPath && !hasFinishedDirectedInitialPath && currentPath == null) {
					nextEvent = nextInitialTransition();
					isDirectedEvent = true;
				} else {
					nextEvent = nextTransition(currentEnabledTransitions);
				}
				if (nextEvent != null) {
					executeEvent(nextEvent, isDirectedEvent);
				} else if (exploredBranchRecorder.getCurrentDepth() == 0) {
					LOG.warn("Finished exploring all states");
				} else if (dmckName.equals("zkChecker-ZAB") && numQueueInitWorkload > 0) {
					if (workloadRetry <= 0) {
						numQueueInitWorkload--;
						workloadRetry = 10;
					}
					workloadRetry--;
					LOG.info("No Transition to execute, but DMCK has not reached termination point. workloadRetry="
							+ workloadRetry);
					try {
						Thread.sleep(steadyStateTimeout);
					} catch (InterruptedException e) {
						LOG.error("", e);
					}
					continue;
				} else {
					loadNextInitialPath(true, true);
					try {
						pathRecordFile.write("duplicated\n".getBytes());
					} catch (IOException e) {
						LOG.error("", e);
					}
					resetTest();
					break;
				}
			}
		}

		protected void executeEvent(Transition nextEvent, boolean isDirectedEvent) {
			collectDebugNextTransition(nextEvent);
			if (isDirectedEvent) {
				LOG.debug("NEXT TRANSITION IS DIRECTED BY INITIAL PATH=" + nextEvent.toString());
			} else {
				LOG.debug("NEXT TRANSITION=" + nextEvent.toString());
				exploredBranchRecorder.createChild(nextEvent.getTransitionId());
				exploredBranchRecorder.traverseDownTo(nextEvent.getTransitionId());
			}
			try {
				currentExploringPath.add(new TransitionTuple(globalState2, nextEvent));
				prevOnlineStatus.add(isNodeOnline.clone());
				prevLocalStates.add(copyLocalState(localStates));
				saveLocalState();

				if (nextEvent instanceof AbstractNodeOperationTransition) {
					AbstractNodeOperationTransition nodeOperationTransition = (AbstractNodeOperationTransition) nextEvent;

					if (nodeOperationTransition.id > -1) {
						nextEvent = ((AbstractNodeOperationTransition) nextEvent)
								.getRealNodeOperationTransition(nodeOperationTransition.id);
						LOG.debug("DMCK is going to follow the suggestion to execute=" + nextEvent.toString());
					} else {
						nextEvent = ((AbstractNodeOperationTransition) nextEvent).getRealNodeOperationTransition();
					}
					nodeOperationTransition.id = ((NodeOperationTransition) nextEvent).getId();
				}

				boolean eventAddedToHistory = false;
				LocalState[] oldLocalStates = copyLocalState(localStates);
				Transition event = null;
				if (isSAMC) {
					if (nextEvent instanceof NodeCrashTransition) {
						event = ((NodeCrashTransition) nextEvent).clone();
					} else if (nextEvent instanceof NodeStartTransition) {
						event = ((NodeStartTransition) nextEvent).clone();
					} else if (nextEvent instanceof PacketSendTransition && enableSymmetry) {
						event = ((PacketSendTransition) nextEvent).clone();
					}
					eventAddedToHistory = addEventToIncompleteHistory(oldLocalStates, event);
				}

				if (nextEvent.apply()) {
					pathRecordFile.write((nextEvent.toString() + "\n").getBytes());
					updateGlobalState();
					updateSAMCQueueAfterEventExecution(nextEvent);
				}

				if (eventAddedToHistory && enableSymmetry) {
					moveIncompleteEventToEventHistory(oldLocalStates, copyLocalState(localStates), event);
				}
			} catch (IOException e) {
				LOG.error("", e);
			}
		}
	}
}
