package com.ys.exch_sim.domain.message;
import com.ys.exch_sim.domain.message.field.ClOrdID;
import com.ys.exch_sim.domain.message.field.OrdType;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.message.field.Tif;
import com.ys.exch_sim.domain.message.field.Timestamp;

import lombok.Value;

@Value
public class New {

    Symbol symbol;
    Px  px;
    Qty qty;
    ClOrdID clOrdID;
    Timestamp ts;
    OrdType ordType;
    Tif tif;

}
