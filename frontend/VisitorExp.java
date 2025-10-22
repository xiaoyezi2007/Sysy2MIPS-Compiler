package frontend;

import llvm.*;
import llvm.constant.ConstantInt;
import llvm.instr.AluInstr;
import llvm.instr.BranchInstr;
import llvm.instr.CallInstr;
import llvm.instr.CmpInstr;
import llvm.instr.GepInstr;
import llvm.instr.Instruction;
import llvm.instr.LoadInstr;

import java.util.ArrayList;

public class VisitorExp {
    Visitor visitor;

    public VisitorExp(Visitor visitor) {
        this.visitor = visitor;
    }

    public Value visit(ASTNode node) {
        return visitAddExp(node.getChildren().get(0));
    }

    public Value visitLOrExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            return visitLAndExp(children.get(0));
        }
        else {
            BasicBlock condBlock = new BasicBlock();
            Value lvalue = visitLOrExp(children.get(0));
            Value cond = lvalue;
            if (lvalue instanceof CmpInstr) {
                Builder.addInstr((Instruction) cond);
                Builder.addInstr(new BranchInstr(cond, Builder.getBranchBlock(true), condBlock));
            }
            else {
                CmpInstr cmp = new CmpInstr(cond,"!=",new ConstantInt(0));
                Builder.addInstr(cmp);
                Builder.addInstr(new BranchInstr(cmp, Builder.getBranchBlock(true), condBlock));
            }
            Builder.addBasicBlock(condBlock);
            return visitLAndExp(children.get(2));
        }
    }

    public Value visitLAndExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            return visitEqExp(children.get(0));
        }
        else {
            BasicBlock condBlock = new BasicBlock();
            Value lvalue = visitLAndExp(children.get(0));
            Value cond = lvalue;
            if (lvalue instanceof CmpInstr) {
                Builder.addInstr((Instruction) cond);
                Builder.addInstr(new BranchInstr(cond, condBlock, Builder.getBranchBlock(false)));
            }
            else {
                CmpInstr cmp = new CmpInstr(cond,"!=",new ConstantInt(0));
                Builder.addInstr(cmp);
                Builder.addInstr(new BranchInstr(cmp, condBlock, Builder.getBranchBlock(false)));
            }
            Builder.addBasicBlock(condBlock);
            return visitEqExp(children.get(2));
        }
    }

    public Value visitEqExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            return visitRelExp(children.get(0));
        }
        else {
            Value lvalue = visitEqExp(children.get(0));
            Value rvalue = visitRelExp(children.get(2));
            return new CmpInstr(lvalue,children.get(1).getValue(),rvalue);
        }
    }

    public Value visitRelExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            return visitAddExp(children.get(0));
        }
        else {
            Value lvalue = visitRelExp(children.get(0));
            Value rvalue = visitAddExp(children.get(2));
            return new CmpInstr(lvalue,children.get(1).getValue(),rvalue);
        }
    }

    public Value visitAddExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            return visitMulExp(children.get(0));
        }
        else {
            Value lvalue = visitAddExp(children.get(0));
            Value rvalue = visitMulExp(children.get(2));
            return new AluInstr(lvalue, children.get(1).getToken().getValue(), rvalue);
        }
    }

    public Value visitMulExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            return visitUnaryExp(children.get(0));
        }
        else {
            Value lvalue = visitMulExp(children.get(0));
            Value rvalue = visitUnaryExp(children.get(2));
            return new AluInstr(lvalue, children.get(1).getToken().getValue(), rvalue);
        }
    }

    public Value visitUnaryExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("PrimaryExp")) {
            return visitPrimaryExp(children.get(0));
        }
        else if (children.get(0).isType("IDENFR")) {
            return visitFuncCall(node);
        }
        else if (children.get(0).isType("UnaryOp")) {
            Value lvalue = new ConstantInt(0);
            Value rvalue = visitUnaryExp(children.get(1));
            if (children.get(0).getValue().equals("!")) {
                return new CmpInstr(lvalue, "==", rvalue);
            }
            return new AluInstr(lvalue, children.get(0).getChildren().get(0).getToken().getValue(), rvalue);
        }
        return null;
    }

    public Value visitFuncCall(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String funcName = children.get(0).getValue();
        if (!visitor.pt.checkSymbol(funcName) && !funcName.equals("getint")) {
            visitor.error.addError("c", children.get(0).getToken().getLine());
            return null;
        }
        if (children.get(2).isType("FuncRParams")) {
            return new CallInstr((Function) visitor.pt.getSymbol(funcName).getValue(),
                visitFuncRParams(children.get(0).getToken().getLine(), funcName, children.get(2)));
        }
        else {
            if (funcName.equals("getint")) {
                return new CallInstr(Builder.getint, new ArrayList<>());
            }
            if (!visitor.pt.getSymbol(funcName).noParam()) visitor.error.addError("d", children.get(0).getToken().getLine());
            return new CallInstr((Function) visitor.pt.getSymbol(funcName).getValue(), new ArrayList<>());
        }
    }

    public ArrayList<Value> visitFuncRParams(int line, String funcName, ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        ArrayList<Integer> types = new ArrayList<>();
        ArrayList<Value> values = new ArrayList<>();
        for (ASTNode child : children) {
            if (child.isType("Exp")) {
                values.add(visit(child));
                types.add(getVarType(child));
            }
        }
        Symbol s = visitor.pt.getSymbol(funcName);
        if (funcName.equals("getint") || types.size() != s.getParamNum()) {
            visitor.error.addError("d", line);
            return values;
        }
        if (!visitor.pt.getSymbol(funcName).checkParams(types)) {
            visitor.error.addError("e", line);
        }
        return values;
    }

    public int getVarType(ASTNode node) {  //so ugly!!!!!!!!
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size()==1&&children.get(0).isType("AddExp")) {
            ASTNode addExp = children.get(0);
            ArrayList<ASTNode> children2 = addExp.getChildren();
            if (children2.size()==1&&children2.get(0).isType("MulExp")) {
                ASTNode mulExp = children2.get(0);
                ArrayList<ASTNode> children3 = mulExp.getChildren();
                if (children3.size()==1&&children3.get(0).isType("UnaryExp")) {
                    ASTNode unaryExp = children3.get(0);
                    ArrayList<ASTNode> children4 = unaryExp.getChildren();
                    if (children4.size()==1&&children4.get(0).isType("PrimaryExp")) {
                        ASTNode primaryExp = children4.get(0);
                        ArrayList<ASTNode> children5 = primaryExp.getChildren();
                        if (children5.size() == 1 && children5.get(0).isType("LVal")) {
                            ASTNode lVal = children5.get(0);
                            ArrayList<ASTNode> children6 = lVal.getChildren();
                            if (children6.size()==1&&children6.get(0).isType("IDENFR")) {
                                String name = children6.get(0).getValue();
                                if (visitor.pt.checkSymbol(name) && visitor.pt.getSymbol(name).isArray()) {
                                    return 1;
                                }
                            }
                        }
                    }
                }
            }
        }
        return 0;
    }

    public Value visitPrimaryExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("LVal")) {
            if (visitor.checkLValc(children.get(0))) {
                ASTNode lval = children.get(0);
                ArrayList<ASTNode> lvalChildren = lval.getChildren();
                Symbol s = visitor.pt.getSymbol(lvalChildren.get(0).getValue());
                if (lval.getChildren().size() == 1 && s.isArray()) {
                    return new GepInstr(s.getValue(), new ConstantInt(0));
                }
                else {
                    return new LoadInstr(visitor.visitLVal(children.get(0)));
                }
            }
        }
        else if (children.get(0).isType("LPARENT")) {
            return visit(children.get(1));
        }
        else if (children.get(0).isType("Number")) {
            return new ConstantInt(Integer.valueOf(children.get(0).getChildren().get(0).getToken().getValue()));
        }
        return null;
    }
}
