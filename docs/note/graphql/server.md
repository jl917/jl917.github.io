# Server

```js
module.exports = "db"; // https://randomuser.me/api/?results=50
```

### typeDefs

```js
const { gql } = require("apollo-server");

module.exports = gql`
  type Name {
    first: String
    last: String
  }
  type User {
    name: Name
    email: String
  }
  type Query {
    users: [User]
  }
  type Mutation {
    addUser(first: String, last: String, email: String): User
  }
`;
```

### resolves

```js
const db = require("./db");

module.exports = {
  Query: {
    users: () => db.results,
  },
  Mutation: {
    addUser: (obj, args, ctx) => {
      console.log(args); // 받은 객체 저장.
      return "등록 완료";
    },
  },
};
```

### app

```js
const { ApolloServer, makeExecutableSchema } = require("apollo-server");
const typeDefs = require("./typeDefs");
const resolvers = require("./resolves");

const schema = makeExecutableSchema({ typeDefs, resolvers });
const server = new ApolloServer({ schema });

// The `listen` method launches a web server.
server.listen().then(({ url }) => {
  console.log(`🚀  Server ready at ${url}`);
});
```
