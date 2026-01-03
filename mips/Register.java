package mips;

import java.util.Arrays;
import java.util.List;

public enum Register {
    ZERO("$zero"),

    V0("$v0"),
    V1("$v1"),

    A0("$a0"),
    A1("$a1"),
    A2("$a2"),
    A3("$a3"),

    T0("$t0"),
    T1("$t1"),
    T2("$t2"),
    T3("$t3"),
    T4("$t4"),
    T5("$t5"),
    T6("$t6"),
    T7("$t7"),

    S0("$s0"),
    S1("$s1"),
    S2("$s2"),
    S3("$s3"),
    S4("$s4"),
    S5("$s5"),
    S6("$s6"),
    S7("$s7"),

    T8("$t8"),
    T9("$t9"),

    K0("$k0"),
    K1("$k1"),

    HI("HI"),
    LO("LO"),

    GP("$gp"),
    SP("$sp"),
    FP("$fp"),
    RA("$ra");




    private final String name;
    private Integer value;

    Register(String name) {
        this.name = name;
    }

    public String toString() {
        return name;
    }

    /**
     * Register pool used by IR-level register allocation.
     *
     * Note: Scratch/temporary registers used during lowering (e.g. $k0/$k1/$t8/$t9/$a1-$a3)
     * are intentionally excluded to avoid clobbering.
     */
    public static List<Register> allocatablePool() {
        return Arrays.asList(
            S0, S1, S2, S3, S4, S5, S6, S7,
            T0, T1, T2, T3, T4, T5, T6, T7,
            A1, A2, A3
        );
    }

    /** Caller-saved temporaries, preferred for non-cross-call values. */
    public static List<Register> callerSavedPool() {
        return Arrays.asList(T0, T1, T2, T3, T4, T5, T6, T7, A1, A2, A3);
    }

    /** Callee-saved registers, safe for values live across calls (with save/restore in prologue/epilogue). */
    public static List<Register> calleeSavedPool() {
        return Arrays.asList(S0, S1, S2, S3, S4, S5, S6, S7);
    }
}
