package frontend;

import llvm.Builder;
import llvm.GlobalValue;
import llvm.GlobalVariable;
import llvm.ReturnType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import llvm.instr.AllocaInstr;
import llvm.instr.Instruction;
import llvm.instr.StoreInstr;

import java.util.ArrayList;

public class VisitorDecl {
    private Visitor visitor;

    public VisitorDecl(Visitor visitor) {
        this.visitor = visitor;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("ConstDecl")) visitConstDecl(children.get(0));
        else if (children.get(0).isType("VarDecl")) {
            visitVarDecl(children.get(0));
        }
    }

    public void visitVarDecl(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String con = "var";
        if (children.get(0).isType("STATICTK")) {
            con = "static";
            String btype = visitBType(children.get(1));
            for (int i = 2; i < children.size(); i++) {
                if (children.get(i).isType("VarDef")) {
                    visitVarDef(children.get(i), con, btype);
                }
            }
        }
        else {
            String btype = visitBType(children.get(0));
            for (int i = 1; i < children.size(); i++) {
                if (children.get(i).isType("VarDef")) {
                    visitVarDef(children.get(i), con, btype);
                }
            }
        }
    }

    public void visitVarDef(ASTNode node, String con, String btype) {
        ArrayList<ASTNode> children = node.getChildren();
        String type = "var";
        if (children.size() > 2 && children.get(1).isType("LBRACK")) {
            type = "array";
        }
        Value value;
        if (visitor.pt.getId() == 1) {
            value = new GlobalVariable(children.get(0).getValue());
            Builder.addGlobalValue((GlobalVariable) value);
        }
        else {
            value = new AllocaInstr();
            Builder.addInstr((AllocaInstr) value);
        }
        visitor.addSymbol(children.get(0).getToken().getLine(), children.get(0).getValue(), type, btype, con, value);
        boolean initFlag = false;
        for (ASTNode child : children) {
            if (child.isType("ConstExp")) {
                visitConstExp(child);
            }
            else if (child.isType("InitVal")) {
                initFlag = true;
                if (visitor.pt.getId() == 1) {
                    ((GlobalValue) value).setValue(visitInitVal(child).getValue());
                }
                else {
                    Value in = visitInitVal(child);
                    Builder.addInstr(new StoreInstr(in, value));
                }
            }
        }
        if (!initFlag && visitor.pt.getId() != 1) {
            Builder.addInstr(new StoreInstr(new ConstantInt(0), value));
        }
        else if (!initFlag) {
            ((GlobalValue) value).setValue(new ConstantInt(0));
        }
    }

    public Value visitInitVal(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        VisitorExp visitorExp = new VisitorExp(visitor);
        if (children.get(0).isType("Exp")) {
            return visitorExp.visit(children.get(0));
        }
        for (ASTNode child : children) {
            if (child.isType("Exp")) {
                visitorExp.visit(child);
            }
        }
        return null;
    }

    public void visitConstDecl(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String con = "const";
        String btype = visitBType(children.get(1));
        for (int i = 2; i < children.size(); i++) {
            if (children.get(i).getName().equals("ConstDef")) {
                visitConstDef(children.get(i), con, btype);
            }
        }
    }

    public String visitBType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }

    public void visitConstDef(ASTNode node, String con, String btype) {
        ArrayList<ASTNode> children = node.getChildren();
        String type = "var";
        if (children.get(1).isType("LBRACK")) type = "array";
        Value value;
        if (visitor.pt.getId() == 1) {
            value = new GlobalVariable(children.get(0).getValue());
            Builder.addGlobalValue((GlobalValue) value);
        }
        else {
            value = new AllocaInstr();
            Builder.addInstr((AllocaInstr) value);
        }
        visitor.addSymbol(children.get(0).getToken().getLine(), children.get(0).getValue(), type, btype, con, value);
        for (ASTNode child : children) {
            if (child.isType("ConstExp")) {
                visitConstExp(child);
            }
            else if (child.isType("ConstInitVal")) {
                if (visitor.pt.getId() == 1) {
                    ((GlobalValue) value).setValue(visitConstInitVal(child).getValue());
                }
                else {
                    Value in = visitConstInitVal(child);
                    Builder.addInstr(new StoreInstr(in, value));
                }
            }
        }
    }

    public Value visitConstExp(ASTNode node) {
        VisitorExp visitorExp = new VisitorExp(visitor);
        return visitorExp.visitAddExp(node.getChildren().get(0));
    }

    public Value visitConstInitVal(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("ConstExp")) {
            return visitConstExp(children.get(0));
        }
        for (ASTNode child : children) {
            if (child.isType("ConstExp")) {
                visitConstExp(child);
            }
        }
        return null;
    }
}
