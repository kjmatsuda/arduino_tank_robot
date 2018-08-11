//グローバル定数宣言
// DONE このピン番号は何を見たら、導き出せる？
// 以下がマニュアル
// https://www.dfrobot.com/wiki/index.php/Arduino_Motor_Shield_(L298N)_(SKU:DRI0009)#Introduction
// 
#define   PIN_CL             5 //E1:左輪モータ回転速度制御のピン
#define   PIN_DL             4 //M1:左輪モータ回転方向制御のピン
#define   PIN_CR             6 //E2:右輪モータ回転速度制御のピン
#define   PIN_DR             7 //M2:右輪モータ回転方向制御のピン

//グローバル変数宣言
String msg = "";

//定常動作関数
void setup() {
  //ボートレット38,400bpsのシリアル通信開始
  Serial.begin(38400);
}

//定常動作関数
void loop() {
  if (Serial.available()) {
    char c = Serial.read();   
    msg += c;
  }
  //シリアル通信フレームの区切り処理
  delay(20);
  if (msg.length() > 0 && !Serial.available()) {  
    int error = process_input();
    if (error) {
      Serial.print(String("ER(") + error + "):" + msg); 
    }
    msg = "";
  }
}

//メッセージ処理関数
int process_input(void) {
  int p;  
  
  String cmd = msg.substring(0,3); 
  if (cmd == "DR(") { //モータ制御
    //control the 2 motors
    if (not isDigit(msg[3])) {
       return 4;
    }
    boolean dir = msg.substring(3).toInt();

    //左モータ回転速度の制御値を読み取る
    p = msg.indexOf(",", 4);
    if (p < 0) {
      return 3;
    }    
    if (not isDigit(msg[p+1])) {
      return 4;
    }
    int left = msg.substring(p+1).toInt();
    
    //右モータ回転速度の制御値を読み取る
    p = msg.indexOf(",", p+2);
    if (p < 0) {
      return 3;
    }     
    if (not isDigit(msg[p+1])) {
      return 4;
    }
    int right = msg.substring(p+1).toInt();    

    // DONE dirの値の範囲は？また、それぞれの値が表す方向は？
    //      1:前進orストップ
    //      0:後進
    // Androidアプリ上のインスタンス変数にmDirがある。
    // mDirは前進で1、後進で-1、ストップで0
    
    // モータの回転方向を設定
    digitalWrite(PIN_DL,dir);
    digitalWrite(PIN_DR,dir);

    // DONE left, rightの値の範囲は？
    // -> 60~180
    // モータの回転速度を設定
    analogWrite(PIN_CL,left);  
    analogWrite(PIN_CR,right);
    
    Serial.print(String("DR(") + dir + String(",") + left + String(",") + right + String(")")); 
  } else if (cmd == "#E0") {  //BLE通信切断処理
    analogWrite(PIN_CL, 0);  
    analogWrite(PIN_CR, 0);
  } else { 
    return 1;
  }    
  return 0;
}
