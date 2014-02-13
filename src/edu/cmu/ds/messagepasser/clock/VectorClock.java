package edu.cmu.ds.messagepasser.clock;

import java.util.ArrayList;

public class VectorClock extends ClockService {
	private ArrayList<Integer> vectorTime = null;
	private Integer processCount = null;
	private Integer localProcessIndex = null;

	public VectorClock(int sum, int index) {
		processCount = sum;
		localProcessIndex = index;
		vectorTime = new ArrayList<Integer>();
		for (int i = 0; i < processCount; ++i) {
			vectorTime.add(0);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public Object incrementAndGetTimeStamp() {
		synchronized (vectorTime) {
			vectorTime.set(localProcessIndex, vectorTime.get(localProcessIndex) + 1);
			return (ArrayList<Integer>) vectorTime.clone();
		}
	}

	/**
	 * Increment vector timestamp at a specific index
	 * 
	 * @param processIndex
	 */
	public void incTimeStamp(int processIndex) {
		synchronized (vectorTime) {
			vectorTime.set(processIndex, vectorTime.get(processIndex) + 1);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public void updateTime(Object timeStamp) {
		ArrayList<Integer> newTime = (ArrayList<Integer>) timeStamp;
		synchronized (vectorTime) {
			vectorTime.set(localProcessIndex, vectorTime.get(localProcessIndex) + 1);
			for (int i = 0; i < vectorTime.size(); ++i) {
				if (newTime.get(i) > vectorTime.get(i)) {
					vectorTime.set(i, newTime.get(i));
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public Object getTimeStamp() {
		synchronized (vectorTime) {
			return (ArrayList<Integer>) vectorTime.clone();
		}
	}

	@Override
	public String toString() {
		return "VectorClock" + vectorTime;
	}
}