package com.ys.exch_sim.domain.message.field;

public enum ExecStatus {
	NEW,
	PARTIAL_FILL,
	FILLED,
	REJECTED , // it happens market order and Limit FOK
	CANCELED
}
