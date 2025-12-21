package llvm.instr;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.MipsBuilder;
import mips.Register;

import java.util.ArrayList;
import java.util.HashMap;

public class AllocaInstr extends Instruction {
    private ArrayList<Value> values = new ArrayList<>();
    public HashMap<BasicBlock, Value> content = new HashMap<>();

    public AllocaInstr(IRType Type) {
        super(ValueType.ALLOCA_INST, new IRType("ptr", Type), Builder.getVarName());
        Builder.addInstr(this);
    }

    public Value findDef(BasicBlock block, ArrayList<BasicBlock> blocks) {
        if (content.containsKey(block)) {
            return content.get(block);
        }
        for (BasicBlock b : block.prev) {
            if (blocks.contains(b)) {continue;}
            blocks.add(b);
            Value v = findDef(b, blocks);
            blocks.remove(blocks.size() - 1);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    public void store(BasicBlock block, Value value) {
        content.put(block, value);
    }

    public Value load(BasicBlock block) {
        if (content.get(block) == null) {
            return load(block.directDom);  //This May Be Wrong!!!!!!!!!!!
        }
        return content.get(block);
    }

    public void addValue(Value value) {
        values.add(value);
    }

    public Value getKthEle(Constant index) {
        if (isConst) {
            return values.get(Integer.valueOf(index.getName()));
        }
        else {
            return null;
        }
    }

    public Constant getValue() {
        if (isConst) {
            return values.get(0).getValue();
        }
        else {
            return null;
        }
    }

    @Override
    public int getSpace() {
        int x = getType().ptTo().size;
        if (x == -1) x = 1;
        return 4*x;
    }

    @Override
    public void toMips() {
        int x = getType().ptTo().size;
        if (x == -1) x = 1;
        memory = MipsBuilder.memory;
        MipsBuilder.memory -= 4*x;
    }

    @Override
    public void print() {
        System.out.println(name+" = alloca "+getType().ptTo().toString());
    }

    @Override
    public boolean isPinned() {
        return true;
    }
}
