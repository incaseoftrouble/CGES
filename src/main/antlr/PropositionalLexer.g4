lexer grammar PropositionalLexer;

@lexer::header {
package com.cges.grammar;
}

TRUE       : 'tt' | 'true' | '1';
FALSE      : 'ff' | 'false' | '0';

NOT        : '!' | 'NOT';

IMP        : '->' | '-->' | '=>' | '==>' | 'IMP';
BIIMP      : '<->' | '<=>' | 'BIIMP';
XOR        : '^' | 'XOR' | 'xor';

AND        : '&&' | '&' | 'AND' ;
OR         : '||' | '|' | 'OR' ;

LPAREN     : '(';
RPAREN     : ')';
LDQUOTE    : '"' -> mode(DOUBLE_QUOTED);
LSQUOTE    : '\'' -> mode(SINGLE_QUOTED);

VARIABLE   : [a-zA-Z_0-9]+;

fragment
WHITESPACE : [ \t\n\r\f]+;
SKIP_DEF   : WHITESPACE -> skip;

ERROR : . ;

mode DOUBLE_QUOTED;
RDQUOTE : '"' -> mode(DEFAULT_MODE);
DOUBLE_QUOTED_VARIABLE : ~["]+;

mode SINGLE_QUOTED;
RSQUOTE : '\'' -> mode(DEFAULT_MODE);
SINGLE_QUOTED_VARIABLE : ~[']+;

