package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import mips.LswInstr;
import mips.RInstr;
import mips.Register;

import java.util.ArrayList;

public class CmpInstr extends Instruction {
    private String op;

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
        loadToReg(lvalue, Register.T0);
        loadToReg(rvalue, Register.T1);
        if (op.equals("==")) {
            new RInstr("seq", Register.T2, Register.T0, Register.T1);
        }
        else if (op.equals("!=")) {
            new RInstr("sne", Register.T2, Register.T0, Register.T1);
        }
        else if (op.equals("<")) {
            new RInstr("slt", Register.T2, Register.T0, Register.T1);
        }
        else if (op.equals("<=")) {
            new RInstr("sle", Register.T2, Register.T0, Register.T1);
        }
        else if (op.equals(">")) {
            new RInstr("sgt", Register.T2, Register.T0, Register.T1);
        }
        else if (op.equals(">=")) {
            new RInstr("sge", Register.T2, Register.T0, Register.T1);
        }
        pushToMem(Register.T2);
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
}
