package frontend;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.Function;
import llvm.IRType;
import llvm.Value;
import llvm.instr.AllocaInstr;
import llvm.instr.StoreInstr;

import java.util.ArrayList;

public class VisitorFuncDef {
    Visitor visitor;
    public VisitorFuncDef(Visitor visitor) {
        this.visitor = visitor;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String returnType = visitFuncType(children.get(0));
        if (children.get(3).isType("FuncFParams")) visitor.FuncFParams = children.get(3);

        Function function = new Function(returnType, children.get(1).getValue());

        Builder.addFunction(function);
        visitor.Func = visitor.addSymbol(children.get(1).getToken().getLine(), children.get(1).getValue(), "func", returnType, function);

        VisitorBlock visitorBlock = new VisitorBlock(visitor);
        ArrayList<Symbol> paras = visitor.beforeBlock();
        Builder.addBasicBlock(new BasicBlock());
        ArrayList<Value> parameters = new ArrayList<>();
        for (Symbol symbol : paras) {
            Value in = symbol.getValue();
            parameters.add(in);
            AllocaInstr to = new AllocaInstr(in.getType());
            Builder.addInstr(to);
            Builder.addInstr(new StoreInstr(in, to));
            symbol.setValue(to);
        }
        function.setParameters(parameters);

        if (children.get(3).isType("FuncFParams")) {
            visitorBlock.visit(children.get(5));
            visitor.afterBlock(children.get(5));
        }
        else {
            visitorBlock.visit(children.get(4));
            visitor.afterBlock(children.get(4));
        }

        Builder.checkReturn();
    }

    public String visitFuncType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }
}
