package com.ys.exch_sim.domain.message.field;

public enum Tif {
    DAY,
    IOC,  // Imediate or Cancel = FAK
	FOK,  // Fill or Kill
	GTC  // Good till cancel
}
