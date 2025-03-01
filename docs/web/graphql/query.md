# Query

### 工具

1. GraphiQL (http://snowtooth.herokuapp.com/graphql)

2. GraphQL Playground (https://www.graphqlbin.com/)

   ```shell
   $ brew cask install praphql-playground
   ```

### 公共 API （https://github.com/APIs-guru/graphql-apis）

### 内置类型

- Int
- FLoat
- String
- Boolean
- ID

参考： http://snowtooth.moonhighway.com

### 查询字段 query

```graphql
## Basic
query liftsAndTrails {
  liftCount(status: OPEN)
  allLifts {
    name
    status
  }
  allTrails {
    name
    diffculty
  }
}
## NameSpace
query liftsAndTrails {
  open: liftCount(status: OPEN)
  chairlifts: allLifts {
    name
    status
  }
  skiSlopes: allTrails {
    name
    diffculty
  }
}
```

### 片段 Fragment

```graphql
fragment liftInfo on Lift {
  name
  status
  capacity
  night
  elevationGain
}
fragment trailInfo on Trail {
  name
  difficulty
}

query {
  Lift(id: "jazz-cat") {
    ...liftInfo
    trailAccess {
      ...trailInfo
    }
  }
  Trail(id: "river-run") {
    ...trailInfo
    accessedByLifts {
      ...liftInfo
    }
  }
}
```

### 变更 mutation

```graphql
mutation createSong {
  addSong(title: "No Scrubs", numberOne: true, performerName: "TLC") {
    id
    title
    numberOne
  }
}
```

### 订阅 subscription

```graphql
subscription {
  liftStatusChange {
    name
    capacity
    status
  }
}

## 修改
mutation closeLift {
  setLiftStatus(id: "astra-express", status: HOLD) {
    name
    status
  }
}
```

### 自检 inrospection ???

```graphql
query {
  __schema {
    types {
      name
      description
    }
  }
}

query {
  __type(name: "Lift") {
    name
    fields {
      name
      description
      type {
        name
      }
    }
  }
}
```
