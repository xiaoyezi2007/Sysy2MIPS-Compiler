package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.LswInstr;
import mips.RInstr;
import mips.Register;

import java.util.ArrayList;

public class CmpInstr extends Instruction {
    private String op;

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public CmpInstr(Value lvalue, String op, Value rvalue) {
        super(ValueType.BINARY_OPERATOR, new IRType("i1"), Builder.getVarName());
        addUseValue(lvalue);
        addUseValue(rvalue);
        this.op = op;
        Builder.addInstr(this);
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
        Register t2 = (!isSpilled() && getAssignedRegister() != null) ? getAssignedRegister() : tmp(2);
        Register lreg = valueOrLoad(lvalue, t0);

        // Fast path for comparisons against an immediate constant.
        // This avoids emitting a per-use `li` inside hot loops (e.g. i < 100).
        if (rvalue instanceof ConstantInt) {
            int imm = Integer.parseInt(rvalue.getName());
            // MIPS slti immediate is 16-bit signed.
            if (op.equals("<") && imm >= -32768 && imm <= 32767) {
                new IInstr("slti", t2, lreg, imm);
                pushToMem(t2);
                return;
            }
            if (op.equals("<=") ) {
                long immPlus1 = (long) imm + 1L;
                if (immPlus1 >= -32768L && immPlus1 <= 32767L) {
                    new IInstr("slti", t2, lreg, (int) immPlus1);
                    pushToMem(t2);
                    return;
                }
            }

            // Use xori + seq/sne for ==/!= when immediate fits.
            // (x == imm)  <=>  (x ^ imm) == 0
            if ((op.equals("==") || op.equals("!=")) && imm >= -32768 && imm <= 32767) {
                new IInstr("xori", t2, lreg, imm);
                if (op.equals("==")) {
                    new RInstr("seq", t2, t2, Register.ZERO);
                }
                else {
                    new RInstr("sne", t2, t2, Register.ZERO);
                }
                pushToMem(t2);
                return;
            }

            // For >= and >, compute the opposite (< or <=) via slti and invert the 0/1 result.
            // (x >= imm) <=> !(x < imm)
            if (op.equals(">=") && imm >= -32768 && imm <= 32767) {
                new IInstr("slti", t2, lreg, imm);
                new IInstr("xori", t2, t2, 1);
                pushToMem(t2);
                return;
            }
            // (x > imm) <=> !(x <= imm)  <=> !(x < imm+1)
            if (op.equals(">")) {
                long immPlus1 = (long) imm + 1L;
                if (immPlus1 >= -32768L && immPlus1 <= 32767L) {
                    new IInstr("slti", t2, lreg, (int) immPlus1);
                    new IInstr("xori", t2, t2, 1);
                    pushToMem(t2);
                    return;
                }
            }
        }

        Register rreg = valueOrLoad(rvalue, t1);
        if (op.equals("==")) {
            new RInstr("seq", t2, lreg, rreg);
        }
        else if (op.equals("!=")) {
            new RInstr("sne", t2, lreg, rreg);
        }
        else if (op.equals("<")) {
            new RInstr("slt", t2, lreg, rreg);
        }
        else if (op.equals("<=")) {
            new RInstr("sle", t2, lreg, rreg);
        }
        else if (op.equals(">")) {
            new RInstr("sgt", t2, lreg, rreg);
        }
        else if (op.equals(">=")) {
            new RInstr("sge", t2, lreg, rreg);
        }
        pushToMem(t2);
    }

    @Override
    public void print() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        System.out.print(name + " = icmp ");
        if (op.equals("==")) {
            System.out.print("eq");
        }
        else if (op.equals("!=")) {
            System.out.print("ne");
        }
        else if (op.equals("<")) {
            System.out.print("slt");
        }
        else if (op.equals("<=")) {
            System.out.print("sle");
        }
        else if (op.equals(">")) {
            System.out.print("sgt");
        }
        else if (op.equals(">=")) {
            System.out.print("sge");
        }
        else {
            System.out.print(op);
        }
        System.out.print(" ");
        System.out.print(rvalue.getTypeName()+" "+lvalue.getName());
        System.out.print(", ");
        System.out.println(rvalue.getName());
    }

    @Override
    public ArrayList<String> tripleString() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        ArrayList<String> ans = new ArrayList<>();
        if (op.equals("==")||op.equals("!=")) {
            ans.add(op+" "+lvalue.getName()+" "+rvalue.getName());
            ans.add(op+" "+rvalue.getName()+" "+lvalue.getName());
        }
        else if (op.equals("<")) {
            ans.add(op+" "+lvalue.getName()+" "+rvalue.getName());
            ans.add("> "+rvalue.getName()+" "+lvalue.getName());
        }
        else if (op.equals("<=")) {
            ans.add(op+" "+lvalue.getName()+" "+rvalue.getName());
            ans.add(">= "+rvalue.getName()+" "+lvalue.getName());
        }
        else if (op.equals(">")) {
            ans.add(op+" "+lvalue.getName()+" "+rvalue.getName());
            ans.add("< "+rvalue.getName()+" "+lvalue.getName());
        }
        else if (op.equals(">=")) {
            ans.add(op+" "+lvalue.getName()+" "+rvalue.getName());
            ans.add("<= "+rvalue.getName()+" "+lvalue.getName());
        }
        return ans;
    }

    @Override
    public Constant getValue() {
        Value lvalue = getUseValue(0);
        Value rvalue = getUseValue(1);
        Constant lconst = lvalue.getValue();
        Constant rconst = rvalue.getValue();
        if (!(lconst instanceof ConstantInt) || !(rconst instanceof ConstantInt)) {
            return null;
        }
        int l = Integer.parseInt(lconst.getName());
        int r = Integer.parseInt(rconst.getName());
        boolean res;
        if (op.equals("==")) {
            res = (l == r);
        }
        else if (op.equals("!=")) {
            res = (l != r);
        }
        else if (op.equals("<")) {
            res = (l < r);
        }
        else if (op.equals("<=")) {
            res = (l <= r);
        }
        else if (op.equals(">")) {
            res = (l > r);
        }
        else if (op.equals(">=")) {
            res = (l >= r);
        }
        else {
            return null;
        }
        return new ConstantInt(res ? 1 : 0);
    }
}
