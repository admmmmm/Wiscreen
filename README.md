#include<iostream>
using namespace std;
int main()
{
    string a;
    do {
        cout << "你是否爱c++？（请用yes或no回答我）" << endl;
        cin >> a;
        if (a == "no") {
            cout << "噢！孩子，这是错误的" << endl;
            cout << endl;
        }
    } while (a != "yes");
        cout << "孩子，这是正确的" << endl;
    
}

