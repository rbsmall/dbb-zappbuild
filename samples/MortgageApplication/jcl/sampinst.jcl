//SAMPINST JOB 1,CLASS=6,MSGCLASS=Y,NOTIFY=&SYSUID
//*
//STEP EXEC CATLPROC,PROG=CATPRC1,DSNME=MYDATA.URMI.INPUT
//          DATAC=MYDATA.BASE.LIB1(DATA1)