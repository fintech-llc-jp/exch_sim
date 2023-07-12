package com.ys.exch_sim.infra;

import lombok.Data;

@Data(staticConstructor = "of")
public class Pair<A, B> {
    public Pair(A left,B right) {
        this.left  = left;
        this.right = right;
    }
    private final A left;
    private final B right;
}