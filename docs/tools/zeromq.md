# zeromq

## 설치

```bash
brew install zeromq
curve_keygen
# or(아래 계속)
npm install zeromq
```

```js
const zmq = require("zeromq");

const { publicKey, secretKey } = zmq.curveKeyPair();
console.log("Public Key:", publicKey);
console.log("Secret Key:", secretKey);
```

## 종류

- push/pull
- pub/sub
- req/rep

## 사용(req/rep)

```js
// server.js
const zmq = require("zeromq");

(async () => {
  const server = new zmq.Reply();

  // 서버의 CurveZMQ 키
  const serverPublicKey = "<서버 공개 키>";
  const serverSecretKey = "<서버 비밀 키>";

  // 서버 설정
  server.curveServer = true;
  server.curvePublicKey = serverPublicKey;
  server.curveSecretKey = serverSecretKey;

  await server.bind("tcp://*:5555");
  console.log("Server started on port 5555");

  for await (const [msg] of server) {
    console.log("Received:", msg.toString());
    server.send("Hello, Client!");
  }
})();

// client.js
const zmq = require("zeromq");

(async () => {
  const client = new zmq.Request();

  // 클라이언트의 CurveZMQ 키
  const clientPublicKey = "<클라이언트 공개 키>";
  const clientSecretKey = "<클라이언트 비밀 키>";

  // 서버의 공개 키
  const serverPublicKey = "<서버 공개 키>";

  client.curvePublicKey = clientPublicKey;
  client.curveSecretKey = clientSecretKey;
  client.curveServerKey = serverPublicKey;

  client.connect("tcp://127.0.0.1:5555");
  console.log("Client connected to server");

  await client.send("Hello, Server!");
  const [reply] = await client.receive();
  console.log("Reply from server:", reply.toString());
})();
```

### Function curveKeyPair

[curveKeyPair](https://zeromq.github.io/zeromq.js/functions/curveKeyPair.html)
