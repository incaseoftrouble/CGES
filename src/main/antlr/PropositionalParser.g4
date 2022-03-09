parser grammar PropositionalParser;

options {
  language = Java;
  tokenVocab = PropositionalLexer;
}

@header {
package com.cges.grammar;
}

formula
  : root=expression EOF
  ;

expression
  : or=orExpression
  ;

orExpression
  : andExpression (OR andExpression)*
  ;

andExpression
  : binaryExpression (AND binaryExpression)*
  ;

binaryExpression
  : left=unaryExpression op=binaryOp right=binaryExpression # binaryOperation
  | unaryExpression # binaryUnary
  ;

unaryExpression
  : op=unaryOp inner=binaryExpression # unaryOperation
  | atomExpression # unaryAtom
  ;

atomExpression
  : constant=bool # boolean
  | variable=VARIABLE # variable
  | LSQUOTE variable=SINGLE_QUOTED_VARIABLE RSQUOTE # variable
  | LDQUOTE variable=DOUBLE_QUOTED_VARIABLE RDQUOTE # variable
  | LPAREN nested=expression RPAREN # nested
  ;

unaryOp
  : NOT
  ;

binaryOp
  : BIIMP | IMP | XOR
  ;

bool
  : TRUE | FALSE
  ;
