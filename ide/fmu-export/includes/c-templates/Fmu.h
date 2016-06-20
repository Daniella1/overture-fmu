/*
 * fmu.h
 *
 *  Created on: Mar 3, 2016
 *      Author: kel
 */

#ifndef FMU_H_
#define FMU_H_

#define BOOL_COUNT 10
#define REAL_COUNT 10
#define INT_COUNT 10

#include "fmi2Functions.h"
#include <stdio.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
//#include <string.h>

struct FmiBuffer{
	 fmi2Boolean booleanBuffer[BOOL_COUNT];
	 fmi2Real realBuffer[REAL_COUNT];
	 fmi2Integer intBuffer[INT_COUNT];
};

struct PeriodicThreadStatus
{
	double period; //1.0E7
	char objectName[100]; //g_System_controller
	char callName[100]; //CLASS_System__Z4loopEV
	int lastExecuted;
};

extern struct FmiBuffer fmiBuffer;
extern const fmi2CallbackFunctions *g_fmiCallbackFunctions;
extern const char* g_fmiInstanceName;

extern struct PeriodicThreadStatus threads[];

void syncInputsToModel();
void syncOutputsToBuffers();
void systemInit();
void systemDeInit();
void systemMain();


void vdmStep(fmi2Real currentCommunicationPoint, fmi2Real communicationStepSize);

#endif /* FMU_H_ */
