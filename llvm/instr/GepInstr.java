package llvm.instr;

import llvm.Builder;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.RInstr;
import mips.Register;
import mips.fake.LaInstr;

import java.util.ArrayList;
import java.util.Objects;

public class GepInstr extends Instruction {
    public GepInstr(Value base, Value index) {
        super(ValueType.GEP_INST, new IRType("ptr", new IRType("i32")), Builder.getVarName());
        addUseValue(base);
        addUseValue(index);
        Builder.addInstr(this);
    }

    @Override
    public int getSpace() {
        return 4;
    }

    @Override
    public void toMips() {
        Value base = getUseValue(0);
        Value index = getUseValue(1);
        Register t0 = tmp(0);
        Register t1 = tmp(1);
        Register t2 = tmp(2);
        if (base instanceof GlobalVariable) {
            new LaInstr(t0, base.getName().substring(1));
            Register idx = valueOrLoad(index, t1);
            new IInstr("sll", t1, idx, 2);
            new RInstr("addu", t2, t0, t1);
            pushToMem(t2);
            Type.isAddr = true;
        }
        else if (base.getType().isAddr) {
            Register baseReg = valueOrLoad(base, t0);
            Register idx = valueOrLoad(index, t1);
            new IInstr("sll", t1, idx, 2);
            new RInstr("addu", t2, baseReg, t1);
            pushToMem(t2);
            Type.isAddr = true;
        }
        else {
            loadAddrToReg(base, t0);
            Register idx = valueOrLoad(index, t1);
            new IInstr("sll", t1, idx, 2);
            new RInstr("addu", t2, t0, t1);
            pushToMem(t2);
            Type.isAddr = true;
        }
    }

    public void print() {
        Value base = getUseValue(0);
        Value index = getUseValue(1);
        System.out.print(name+" = getelementptr inbounds "+base.getType().ptTo().toString()
        +", "+base.getTypeName()+" "+base.getName()+", ");
        if (base.getType().ptTo().isArray()) {
            System.out.print("i32 0, ");
        }
        System.out.println(index.getTypeName()+" "+index.getName());
    }

    public Constant getValue() {
        Value index = getUseValue(1);
        Constant id = index.getValue();
        if (id == null) return null;
        Value base = getUseValue(0);
        if (!base.isConst) return null;
        if (base instanceof AllocaInstr) {
            return ((AllocaInstr) base).getKthEle(id).getValue();
        }
        else if (base instanceof GlobalVariable) {
            return ((GlobalVariable) base).getKthEle(id).getValue();
        }
        return null;
    }
/*
    @Override
    public boolean equals(Object object) {
        Value index = getUseValue(1);
        Value base = getUseValue(0);
        if (object instanceof GepInstr) {
            GepInstr other = (GepInstr) object;
            Value otherIndex = other.getUseValue(1);
            Value otherBase = other.getUseValue(0);
            return base.getName().equals(otherBase.getName()) && index.getName().equals(otherIndex.getName());
        }
        return false;
    }*/

    @Override
    public int hashCode() {
        Value index = getUseValue(1);
        Value base = getUseValue(0);
        return Objects.hash(base.getName(), index.getName());
    }

    @Override
    public ArrayList<String> tripleString() {
        ArrayList<String> list = new ArrayList<>();
        Value index = getUseValue(1);
        Value base = getUseValue(0);
        list.add("gep "+base.getName()+" "+index.getName());
        return list;
    }
}
