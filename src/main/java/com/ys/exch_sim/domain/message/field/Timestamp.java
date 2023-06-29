package com.ys.exch_sim.domain.message.field;

import java.time.LocalDateTime;

import lombok.Value;

@Value
public class Timestamp {
    private LocalDateTime ts;
}
