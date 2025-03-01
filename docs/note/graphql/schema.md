# SCHEMA

你要对 API 包含的数据类型进行统筹，讨论并正式确定下来。 这种类型的集合就是后端程序员所熟知的 schema

schema 优先是一种设计方法论，遵循它可以使团队保持一致的数据类型。

### TYPE

```graphql
type Photo {
  id: ID! # 非空字段 non-nullable
  name: String!
  url: String!
  description: String
}
```

### 标量类型 scalar

https://www.npmjs.com/package/graphql-custom-types

```graphql
scalar DataTime
type Photo {
  id: ID!
  name: String!
  url: String!
  description: String
  created: DateTime!
}
```

```js
import { GraphQLScalarType } from "graphql";

const myCustomScalarType = new GraphQLScalarType({
  name: "MyCustomScalar",
  description: "Description of my custom scalar type",
  serialize(value) {
    let result;
    // 从服务端发送给客户端的数据
    return result;
  },
  parseValue(value) {
    // 从客户端接收的数据
    let result;
    return result;
  },
  parseLiteral(ast) {
    switch (ast.kind) {
    }
  },
});

const schemaString = `
scalar MyCustomScalar

type Foo {
  aField: MyCustomScalar
}

type Query {
  foo: Foo
}
`;
```

### 枚举 enum

```graphql
enum PhotoCategory {
  SELFIE
  PORTRAIT
  ACTION
  LANDSCAPE
  GRAPHIC
}
```

### 列表 list

```
[String]
[Int] # 可空的整数值列表
# [] => ok, [null,1,2] => ok
[Int!] # 不可空的整数值列表
# [] => ok, [null,1,2] => error
[Int]! # 可空的整数值非空列表
# [] => error, [null,1,2] => ok
[Int!]! # 不可空的整数值非空列表
# [] => error, [null,1,2] => error
```

### 连接 Linked

#### 一对一

```
type User {
	username: ID!
	name: String
	avatar: String
}
type Photo {
	id: ID!
	url: String!
	description: String
	created: DateTime!
	postedBy: User!
}
```

#### 一对多

```
type User {
	username: ID!
	name: String
	avatar: String
	photos: [Photo!]
}
type Photo {
	id: ID!
	url: String!
	description: String
	created: DateTime!
	postedBy: User!
}
```

#### 多对多？？

#### 直通类型

```
type User {
	username: ID!
	name: String
	avatar: String
}
type User {
	friends [User!]!
}
```

### 联合类型 union type

### 接口 interface

### 参数 argument

```
type Query {
	# 查询
	User(id: ID!): User!
	# 筛选
	allPhotos(category: PhotoCategory): [Photo!]!
}
```

####

### 输入
