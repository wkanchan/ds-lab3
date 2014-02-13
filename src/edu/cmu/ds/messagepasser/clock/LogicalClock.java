package edu.cmu.ds.messagepasser.clock;

public class LogicalClock extends ClockService {
	private Integer logicalTime = -1;

	public Object incrementAndGetTimeStamp() {
		synchronized (logicalTime) {
			return ++logicalTime;
		}
	}

	@Override
	public void updateTime(Object timeStamp) {
		Integer newTime = (Integer) timeStamp;
		synchronized (logicalTime) {
			if (logicalTime >= newTime) {
				logicalTime++;
			} else {
				logicalTime = newTime + 1;
			}
		}
	}

	@Override
	public Object getTimeStamp() {
		return logicalTime;
	}
}
