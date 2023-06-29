package com.ys.exch_sim.domain.message.field;

public enum OrdStatus {
	PENDING_NEW,
	NEW,
	PARTIAL_FILL,
	FILLED,
	 REJECTED , // it happens market order and Limit FOK
    PENDING_CANCEL,
	CANCELED,
	CANCEL_REJECTED,
}
