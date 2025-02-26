/*#include <iostream>
#include <string>
#include <algorithm>
using namespace std;

int main()
{
	string ans;
	int input, rem;
	char c;
	cin >> input;
	
	do {
		rem = input % 2;
		input = input / 2;
		
		char c = rem + 48;
		ans.push_back(c);
	} while (input>=1);
	
	reverse(ans.begin(), ans.end());
	cout << ans << endl;
	
	return 0;
}


#include <iostream>
using namespace std;

int main() {
	int b, s, g, i;
	for (i = 10; i < 1000; i++) {
		g = (i % 10);
		s = (i - g) % 100 / 10;
		b = (i - g - 10 * s) / 100;
		if (b == 0) {
			if (s * g > s + g)
				cout << i << endl;
		}
		else {
			if (b * s * g > b + s + g)
				cout << i << " ";
		}
	}

	return 0;
}
#include <iostream>
#include <algorithm>
#include <vector>
#include <iomanip>
using namespace std;

int main() {
	vector<int> vec;
	float sum = 0;
	for (int i = 0; i < 10; i++) {
		int a;
		cin >> a;
		vec.push_back(a);
	}
		auto max_it = max_element(vec.begin(), vec.end());
		auto it1 = find(vec.begin(), vec.end(), *max_it);
		if (it1 != vec.end()) {
			vec.erase(it1);
		}
		auto min_it = min_element(vec.begin(), vec.end());
		auto it2 = find(vec.begin(), vec.end(), *min_it);
		if (it2 != vec.end()) {
			vec.erase(it2);
		}
		for (int v : vec) {
			
			sum += v;
		}
		cout << fixed << setprecision(3) << sum / 8 << endl;
		return 0;
}*/
/*
#include <iostream>
#include <cmath>
#include <iomanip>
using namespace std;

int main() {
	int i = 0;
	double ai, sum = 0;
	do {
		ai = 1.0 / (2.0 * i + 1.0) * pow(-1, i);
		sum += ai;
		i = i + 1;
	} while (abs(ai) >= 1e-6);
	ai = 1.0 / (2.0 * i + 1.0) * pow(-1, i);
	sum += ai;
	cout << fixed << setprecision(8) << sum * 4.0;
	return 0;
}*/

#include <iostream>
#include <vector>
using namespace std;

struct date {
	int year;
	int month;
	int day;
	int leap() {
		if (year % 4 == 0 && year % 100 != 0)
			return 0;//闰年，二月有29天
		else
			return 1;//平年，二月有28天
	}
};

int main() {
	date dtin;
	int num, approx, result;
	result = dtin.leap();
	vector<int>corr = { 0, 1,0,1,1,2,2,3,4,4,5,5 };
	cin >> dtin.year >> dtin.month >> dtin.day;
	approx = (dtin.month - 1) * 30 + dtin.day;
	if ( result == 0) 
		num = approx + corr[dtin.month-1];
	else
		if (dtin.month<=2)
			num = approx + corr[dtin.month-1];
		else 
			num = approx + corr[dtin.month-1]-1;
	
	cout << num << endl;
	return 0;
}