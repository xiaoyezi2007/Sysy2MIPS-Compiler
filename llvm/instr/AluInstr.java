package llvm.instr;

import llvm.ReturnType;
import llvm.*;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.JInstr;
import mips.IInstr;
import mips.RInstr;
import mips.Register;
import mips.SpecialInstr;
import mips.fake.LiInstr;

import java.util.ArrayList;

public class AluInstr extends Instruction {
    String op;

    private static class Magic {
        final int m;
        final int s;

        Magic(int m, int s) {
            this.m = m;
            this.s = s;
        }
    }

    public AluInstr(Value lvalue, String op, Value rvalue) {
        super(ValueType.BINARY_OPERATOR, new IRType("i32"), Builder.getVarName());
        this.op = op;
        addUseValue(lvalue);
        addUseValue(rvalue);
        Builder.addInstr(this);
    }

    /**
     * Optimization-friendly constructor.
     * When addToBuilder is false, the instruction is created detached and must be inserted
     * into a basic block instruction list manually by the caller.
     */
    public AluInstr(Value lvalue, String op, Value rvalue, String explicitName, boolean addToBuilder) {
        super(ValueType.BINARY_OPERATOR, new IRType("i32"), explicitName);
        this.op = op;
        addUseValue(lvalue);
        addUseValue(rvalue);
        if (addToBuilder) {
            Builder.addInstr(this);
        }
    }


    @Override
    public void print() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        System.out.print(name + " = ");
        if (op.equals("+")) {
            System.out.print("add");
        }
        else if (op.equals("-")) {
            System.out.print("sub");
        }
        else if (op.equals("*")) {
            System.out.print("mul");
        }
        else if (op.equals("/")) {
            System.out.print("sdiv");
        }
        else if (op.equals("%")) {
            System.out.print("srem");
        }
        System.out.println(" "+lvalue.getTypeName()+" "+lvalue.getName()+", "+rvalue.getName());
    }

    @Override
    public int getSpace() {
        return 4;
    }

    @Override
    public void toMips() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        Register t0 = tmp(0);
        Register t1 = tmp(1);
        // If this SSA value is register-allocated, compute the result directly into that register.
        // This avoids a very common pattern: tmp-result -> move into assigned register.
        Register t2 = (!isSpilled() && getAssignedRegister() != null) ? getAssignedRegister() : tmp(2);
        Register t3 = tmp(3);
        Register t4 = tmp(4);
        Register t5 = tmp(5);

        // Strength reduction for division/modulo by power-of-two constants:
        // Use bias + arithmetic shift to implement C/LLVM-style trunc-toward-zero division.
        // For d = 2^k:
        //   q = (x + ((x >> 31) & ((1<<k)-1))) >> k
        // For negative d, negate q. Remainder computed as r = x - q*d.
        if ((op.equals("/") || op.equals("%")) && (rvalue instanceof ConstantInt)) {
            int d = Integer.parseInt(rvalue.getName());
            if (d != 0) {
                if (d == 1) {
                    if (op.equals("/")) {
                        Register lx = valueOrLoad(lvalue, t0);
                        new RInstr("addu", t2, lx, Register.ZERO);
                        pushToMem(t2);
                        return;
                    }
                    // x % 1 == 0
                    new RInstr("addu", t2, Register.ZERO, Register.ZERO);
                    pushToMem(t2);
                    return;
                }
                if (d == -1) {
                    if (op.equals("/")) {
                        Register lx = valueOrLoad(lvalue, t0);
                        new RInstr("subu", t2, Register.ZERO, lx);
                        pushToMem(t2);
                        return;
                    }
                    // x % -1 == 0
                    new RInstr("addu", t2, Register.ZERO, Register.ZERO);
                    pushToMem(t2);
                    return;
                }

                int abs = (d == Integer.MIN_VALUE) ? Integer.MIN_VALUE : Math.abs(d);
                boolean pow2;
                int k;
                if (d == Integer.MIN_VALUE) {
                    pow2 = true;
                    k = 31;
                }
                else {
                    pow2 = (abs & (abs - 1)) == 0;
                    k = Integer.numberOfTrailingZeros(abs);
                }

                if (pow2 && k > 0) {
                    Register xreg = valueOrLoad(lvalue, t0); // x

                    // If the allocator reuses the same physical register for both
                    // the numerator and this instruction's result, computing q into
                    // t2 would clobber x. For remainder we must preserve x.
                    Register xSaved = xreg;
                    if (op.equals("%") && xreg == t2) {
                        new RInstr("addu", t1, xreg, Register.ZERO);
                        xSaved = t1;
                    }

                    // sign = x >> 31
                    new IInstr("sra", t4, xreg, 31);
                    // mask = (1<<k)-1 (k in [1,30])
                    int mask = (k == 31) ? 0x7fffffff : ((1 << k) - 1);
                    new LiInstr(t5, mask);
                    // bias = sign & mask
                    new RInstr("and", t4, t4, t5);
                    // tmp = x + bias
                    new RInstr("addu", t4, xreg, t4);
                    // q = tmp >> k (arith)
                    new IInstr("sra", t2, t4, k);

                    if (d < 0) {
                        new RInstr("subu", t2, Register.ZERO, t2);
                    }

                    if (op.equals("/")) {
                        pushToMem(t2);
                        return;
                    }

                    // r = x - q*d
                    // tmp2 = q * abs (shift)
                    new IInstr("sll", t3, t2, k);
                    if (d < 0) {
                        // tmp2 = q * d = -(q*abs)
                        new RInstr("subu", t3, Register.ZERO, t3);
                    }
                    new RInstr("subu", t2, xSaved, t3);
                    pushToMem(t2);
                    return;
                }

                // General constant division by magic number (Granlund/Montgomery style).
                // Uses one mult (HI part) + shifts/adds, and is guarded by a small self-check.
                int absD = (d == Integer.MIN_VALUE) ? Integer.MIN_VALUE : Math.abs(d);
                Magic magic = computeMagicSignedPositive(absD);
                if (magic != null && magicPassesSelfCheck(d, absD, magic)) {
                    Register xreg = valueOrLoad(lvalue, t0); // x

                    // If dest aliases numerator, mfhi into t2 would clobber x.
                    // The magic-number algorithm also needs x after mfhi (sign fix,
                    // optional add, remainder reconstruction), so preserve it.
                    Register xForAlgo = xreg;
                    if (xreg == t2) {
                        new RInstr("addu", t4, xreg, Register.ZERO);
                        xForAlgo = t4;
                    }

                    // mulhi = high32(x * m)
                    new LiInstr(t1, magic.m);
                    new RInstr("mult", Register.HI, xForAlgo, t1);
                    new SpecialInstr("mfhi", t2);

                    if (magic.m < 0) {
                        new RInstr("addu", t2, t2, xForAlgo);
                    }
                    if (magic.s != 0) {
                        new IInstr("sra", t2, t2, magic.s);
                    }
                    // q = q - (x >> 31)  (fix trunc toward zero)
                    new IInstr("sra", t3, xForAlgo, 31);
                    new RInstr("subu", t2, t2, t3);

                    if (d < 0) {
                        new RInstr("subu", t2, Register.ZERO, t2);
                    }

                    if (op.equals("/")) {
                        pushToMem(t2);
                        return;
                    }

                    // r = x - q*d
                    new LiInstr(t1, d);
                    new RInstr("mul", t3, t2, t1);
                    new RInstr("subu", t2, xForAlgo, t3);
                    pushToMem(t2);
                    return;
                }
            }
        }

        // Strength reduction for multiplication by small-popcount constants:
        // If C's binary representation has <= 2 one-bits, compute via shifts/adds.
        // This reduces expensive MULT usage (score weight=5) without changing semantics.
        if (op.equals("*")) {
            Integer constVal = null;
            Value varVal = null;
            if (lvalue instanceof ConstantInt) {
                constVal = Integer.parseInt(lvalue.getName());
                varVal = rvalue;
            }
            else if (rvalue instanceof ConstantInt) {
                constVal = Integer.parseInt(rvalue.getName());
                varVal = lvalue;
            }

            if (constVal != null && varVal != null) {
                if (constVal == 0) {
                    new RInstr("addu", t2, Register.ZERO, Register.ZERO);
                    pushToMem(t2);
                    return;
                }
                if (constVal == 1) {
                    Register vx = valueOrLoad(varVal, t0);
                    new RInstr("addu", t2, vx, Register.ZERO);
                    pushToMem(t2);
                    return;
                }
                if (constVal == -1) {
                    Register vx = valueOrLoad(varVal, t0);
                    new RInstr("subu", t2, Register.ZERO, vx);
                    pushToMem(t2);
                    return;
                }

                int abs;
                boolean needNeg = false;
                if (constVal == Integer.MIN_VALUE) {
                    // 0x80000000: equivalent to (x << 31) in 32-bit wraparound.
                    abs = Integer.MIN_VALUE; // sentinel
                }
                else if (constVal < 0) {
                    abs = -constVal;
                    needNeg = true;
                }
                else {
                    abs = constVal;
                }

                // Handle MIN_VALUE separately (single top bit).
                if (constVal == Integer.MIN_VALUE) {
                    Register vx = valueOrLoad(varVal, t0);
                    new IInstr("sll", t2, vx, 31);
                    pushToMem(t2);
                    return;
                }

                int bitCount = Integer.bitCount(abs);
                if (bitCount > 0 && bitCount <= 2) {
                    Register vx = valueOrLoad(varVal, t0);
                    int first = Integer.numberOfTrailingZeros(abs);
                    int rest = abs & (abs - 1);
                    if (rest != 0 && first == 0) {
                        // abs = 1 + 2^k : compute vx + (vx << k) directly (no copy-to-dest move).
                        int second = Integer.numberOfTrailingZeros(rest);
                        new IInstr("sll", t3, vx, second);
                        new RInstr("addu", t2, vx, t3);
                    }
                    else {
                        new IInstr("sll", t2, vx, first);
                        if (rest != 0) {
                            int second = Integer.numberOfTrailingZeros(rest);
                            new IInstr("sll", t3, vx, second);
                            new RInstr("addu", t2, t2, t3);
                        }
                    }
                    if (needNeg) {
                        new RInstr("subu", t2, Register.ZERO, t2);
                    }
                    pushToMem(t2);
                    return;
                }
            }
        }

        // Fast paths for add/sub with an immediate constant.
        // This avoids emitting a per-use `li` (common in counted loops like i = i + 1).
        if (op.equals("+")) {
            Value var = lvalue;
            Value con = rvalue;
            if (lvalue instanceof ConstantInt && !(rvalue instanceof ConstantInt)) {
                var = rvalue;
                con = lvalue;
            }
            if (con instanceof ConstantInt && !(var instanceof ConstantInt)) {
                int imm = Integer.parseInt(con.getName());
                if (imm >= -32768 && imm <= 32767) {
                    Register vreg = valueOrLoad(var, t0);
                    new IInstr("addi", t2, vreg, imm);
                    pushToMem(t2);
                    return;
                }
            }
        }
        else if (op.equals("-")) {
            if (rvalue instanceof ConstantInt && !(lvalue instanceof ConstantInt)) {
                int imm = Integer.parseInt(rvalue.getName());
                long neg = -(long) imm;
                if (neg >= -32768L && neg <= 32767L) {
                    Register vreg = valueOrLoad(lvalue, t0);
                    new IInstr("addi", t2, vreg, (int) neg);
                    pushToMem(t2);
                    return;
                }
            }
        }

        Register lreg = valueOrLoad(lvalue, t0);
        Register rreg = valueOrLoad(rvalue, t1);
        if (op.equals("+")) {
            new RInstr("addu", t2, lreg, rreg);
            pushToMem(t2);
        }
        else if (op.equals("-")) {
            new RInstr("subu", t2, lreg, rreg);
            pushToMem(t2);
        }
        else if (op.equals("*")) {
            new RInstr("mul", t2, lreg, rreg);
            pushToMem(t2);
        }
        else if (op.equals("/")) {
            new RInstr("div", Register.LO, lreg, rreg);
            new SpecialInstr("mflo", t2);
            pushToMem(t2);
        }
        else if (op.equals("%")) {
            new RInstr("div", Register.HI, lreg, rreg);
            new SpecialInstr("mfhi", t2);
            pushToMem(t2);
        }
    }

    @Override
    public Constant getValue() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        Constant lconst = lvalue.getValue();
        Constant rconst = rvalue.getValue();
        if (lconst == null || rconst == null) {
            return null;
        }
        return lvalue.getValue().cal(op, rvalue.getValue());
    }

    @Override
    public ArrayList<String> tripleString() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        ArrayList<String> ans = new ArrayList<>();
        if (op.equals("+")||op.equals("*")) {
            ans.add(op+" "+lvalue.getName()+" "+rvalue.getName());
            ans.add(op+" "+rvalue.getName()+" "+lvalue.getName());
        }
        else {
            ans.add(op+" "+lvalue.getName()+" "+rvalue.getName());
        }
        return ans;
    }

    @Override
    public boolean isPinned() {
        // Division/modulo can trap on divisor == 0 in MIPS.
        // GCM must not speculate these across control-flow.
        return op.equals("/") || op.equals("%");
    }

    public String getOperator() {
        return op;
    }

    // Compute magic number for signed division by constant d.
    // Returns (m, s) such that quotient can be computed as:
    //   q = high32(m*x);
    //   if (m < 0) q += x;
    //   q >>= s;
    //   q -= (x >> 31);
    //   if (d < 0) q = -q;
    private static Magic computeMagicSignedPositive(int d) {
        if (d == 0 || d == 1) {
            return null;
        }
        // Power-of-two handled elsewhere.
        if (d != Integer.MIN_VALUE && (d & (d - 1)) == 0) {
            return null;
        }

        long two31 = 1L << 31;
        long adl = (d == Integer.MIN_VALUE) ? (1L << 31) : (long) d;
        long t = two31;
        long anc = t - 1 - (t % adl);
        long p = 31;
        long q1 = two31 / anc;
        long r1 = two31 - q1 * anc;
        long q2 = two31 / adl;
        long r2 = two31 - q2 * adl;
        long delta;
        do {
            p++;
            q1 <<= 1;
            r1 <<= 1;
            if (r1 >= anc) {
                q1++;
                r1 -= anc;
            }
            q2 <<= 1;
            r2 <<= 1;
            if (r2 >= adl) {
                q2++;
                r2 -= adl;
            }
            delta = adl - r2;
        } while (q1 < delta || (q1 == delta && r1 == 0));

        long m = q2 + 1;
        int s = (int) (p - 32);
        return new Magic((int) m, s);
    }

    private static int simulateMagicQuot(int x, int d, Magic magic) {
        long prod = (long) magic.m * (long) x;
        int hi = (int) (prod >> 32);
        if (magic.m < 0) {
            hi = hi + x;
        }
        int q = (magic.s == 0) ? hi : (hi >> magic.s);
        q = q - (x >> 31);
        if (d < 0) {
            q = -q;
        }
        return q;
    }

    private static boolean magicPassesSelfCheck(int d, int absD, Magic magic) {
        // Deterministic quick-check for correctness; if it fails, we keep real div.
        int[] xs = new int[] {
                0, 1, -1, 2, -2, 3, -3, 7, -7, 8, -8, 15, -15, 16, -16,
                Integer.MAX_VALUE, Integer.MIN_VALUE, 0x40000000, 0x7ffffffe, 0x80000001
        };
        for (int x : xs) {
            int got = simulateMagicQuot(x, d, magic);
            int exp = x / d;
            if (got != exp) {
                return false;
            }
        }
        // Add a few pseudo-random values (fixed seed) to catch corner cases.
        int seed = 0x13579BDF ^ d ^ absD;
        for (int i = 0; i < 24; i++) {
            seed = seed * 1103515245 + 12345;
            int x = seed;
            int got = simulateMagicQuot(x, d, magic);
            int exp = x / d;
            if (got != exp) {
                return false;
            }
        }
        return true;
    }
}
