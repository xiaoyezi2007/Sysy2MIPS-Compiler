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
                        loadToReg(lvalue, Register.T0);
                        new RInstr("addu", Register.T2, Register.T0, Register.ZERO);
                        pushToMem(Register.T2);
                        return;
                    }
                    // x % 1 == 0
                    new RInstr("addu", Register.T2, Register.ZERO, Register.ZERO);
                    pushToMem(Register.T2);
                    return;
                }
                if (d == -1) {
                    if (op.equals("/")) {
                        loadToReg(lvalue, Register.T0);
                        new RInstr("subu", Register.T2, Register.ZERO, Register.T0);
                        pushToMem(Register.T2);
                        return;
                    }
                    // x % -1 == 0
                    new RInstr("addu", Register.T2, Register.ZERO, Register.ZERO);
                    pushToMem(Register.T2);
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
                    loadToReg(lvalue, Register.T0); // x

                    // sign = x >> 31
                    new IInstr("sra", Register.T4, Register.T0, 31);
                    // mask = (1<<k)-1 (k in [1,30])
                    int mask = (k == 31) ? 0x7fffffff : ((1 << k) - 1);
                    new LiInstr(Register.T5, mask);
                    // bias = sign & mask
                    new RInstr("and", Register.T4, Register.T4, Register.T5);
                    // tmp = x + bias
                    new RInstr("addu", Register.T4, Register.T0, Register.T4);
                    // q = tmp >> k (arith)
                    new IInstr("sra", Register.T2, Register.T4, k);

                    if (d < 0) {
                        new RInstr("subu", Register.T2, Register.ZERO, Register.T2);
                    }

                    if (op.equals("/")) {
                        pushToMem(Register.T2);
                        return;
                    }

                    // r = x - q*d
                    // tmp2 = q * abs (shift)
                    new IInstr("sll", Register.T3, Register.T2, k);
                    if (d < 0) {
                        // tmp2 = q * d = -(q*abs)
                        new RInstr("subu", Register.T3, Register.ZERO, Register.T3);
                    }
                    new RInstr("subu", Register.T2, Register.T0, Register.T3);
                    pushToMem(Register.T2);
                    return;
                }

                // General constant division by magic number (Granlund/Montgomery style).
                // Uses one mult (HI part) + shifts/adds, and is guarded by a small self-check.
                int absD = (d == Integer.MIN_VALUE) ? Integer.MIN_VALUE : Math.abs(d);
                Magic magic = computeMagicSignedPositive(absD);
                if (magic != null && magicPassesSelfCheck(d, absD, magic)) {
                    loadToReg(lvalue, Register.T0); // x

                    // mulhi = high32(x * m)
                    new LiInstr(Register.T1, magic.m);
                    new RInstr("mult", Register.HI, Register.T0, Register.T1);
                    new SpecialInstr("mfhi", Register.T2);

                    if (magic.m < 0) {
                        new RInstr("addu", Register.T2, Register.T2, Register.T0);
                    }
                    if (magic.s != 0) {
                        new IInstr("sra", Register.T2, Register.T2, magic.s);
                    }
                    // q = q - (x >> 31)  (fix trunc toward zero)
                    new IInstr("sra", Register.T3, Register.T0, 31);
                    new RInstr("subu", Register.T2, Register.T2, Register.T3);

                    if (d < 0) {
                        new RInstr("subu", Register.T2, Register.ZERO, Register.T2);
                    }

                    if (op.equals("/")) {
                        pushToMem(Register.T2);
                        return;
                    }

                    // r = x - q*d
                    new LiInstr(Register.T1, d);
                    new RInstr("mul", Register.T3, Register.T2, Register.T1);
                    new RInstr("subu", Register.T2, Register.T0, Register.T3);
                    pushToMem(Register.T2);
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
                    new RInstr("addu", Register.T2, Register.ZERO, Register.ZERO);
                    pushToMem(Register.T2);
                    return;
                }
                if (constVal == 1) {
                    loadToReg(varVal, Register.T0);
                    new RInstr("addu", Register.T2, Register.T0, Register.ZERO);
                    pushToMem(Register.T2);
                    return;
                }
                if (constVal == -1) {
                    loadToReg(varVal, Register.T0);
                    new RInstr("subu", Register.T2, Register.ZERO, Register.T0);
                    pushToMem(Register.T2);
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
                    loadToReg(varVal, Register.T0);
                    new IInstr("sll", Register.T2, Register.T0, 31);
                    pushToMem(Register.T2);
                    return;
                }

                int bitCount = Integer.bitCount(abs);
                if (bitCount > 0 && bitCount <= 2) {
                    loadToReg(varVal, Register.T0);
                    int first = Integer.numberOfTrailingZeros(abs);
                    int rest = abs & (abs - 1);
                    new IInstr("sll", Register.T2, Register.T0, first);
                    if (rest != 0) {
                        int second = Integer.numberOfTrailingZeros(rest);
                        new IInstr("sll", Register.T3, Register.T0, second);
                        new RInstr("addu", Register.T2, Register.T2, Register.T3);
                    }
                    if (needNeg) {
                        new RInstr("subu", Register.T2, Register.ZERO, Register.T2);
                    }
                    pushToMem(Register.T2);
                    return;
                }
            }
        }

        loadToReg(lvalue, Register.T0);
        loadToReg(rvalue, Register.T1);
        if (op.equals("+")) {
            new RInstr("addu", Register.T2, Register.T0, Register.T1);
            pushToMem(Register.T2);
        }
        else if (op.equals("-")) {
            new RInstr("subu", Register.T2, Register.T0, Register.T1);
            pushToMem(Register.T2);
        }
        else if (op.equals("*")) {
            new RInstr("mul", Register.T2, Register.T0, Register.T1);
            pushToMem(Register.T2);
        }
        else if (op.equals("/")) {
            new RInstr("div", Register.LO, Register.T0, Register.T1);
            new SpecialInstr("mflo", Register.T2);
            pushToMem(Register.T2);
        }
        else if (op.equals("%")) {
            new RInstr("div", Register.HI, Register.T0, Register.T1);
            new SpecialInstr("mfhi", Register.T2);
            pushToMem(Register.T2);
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
