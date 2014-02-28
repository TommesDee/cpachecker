int main(int argc,char* argv[])
{
	int a = 0;
	int i = 0;
	int c = 0;

	while(i<10) {
		i++;
		a++;
	}
//--INTERPOLATION---
	c = i;
	if(c==5) {
		c = 23;
		a++;
		a--;
	}

	if(a<10) {
		ERROR:
		goto ERROR;
	}

	return 0;
}

/* Beispielrechnung:
a0=0 & i0=0 & c0=0 & i1=i0+1 & a1=a0+1 (& ax-ix=a1-i1)
i1>=10 & c1=i1 & a1<10 (& ax-ix=a1-i1)
=> a1-i1=0
*/
