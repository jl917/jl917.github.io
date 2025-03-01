# Client

```sh
npm install --save @apollo/client graphql
```

### Client

```js
import { ApolloClient, InMemoryCache } from "@apollo/client";

const client = new ApolloClient({
  uri: "http://localhost:4000",
  cache: new InMemoryCache(),
  defaultOptions: {
    watchQuery: {
      fetchPolicy: "no-cache",
      errorPolicy: "ignore",
    },
    query: {
      fetchPolicy: "no-cache",
      errorPolicy: "all",
    },
  },
});

export default client;
```

### ApolloProvider

```jsx
render(
  <ApolloProvider client={client}>
    <Provider store={store}>
      <Router />
    </Provider>
  </ApolloProvider>,
  document.getElementById("app")
);
```

### Mutation

```jsx
import React, { useState } from "react";
import { Form, Input, Button, InputNumber } from "antd";
import { useMutation, gql } from "@apollo/client";

// graphql부분(1)
const ADD_USER = gql`
  mutation AddUser($first: String, $last: String, $email: String) {
    addUser(first: $first, last: $last, email: $email) {
      email
    }
  }
`;

const Page = () => {
  const [resetKey, setResetKey] = useState(0);
  // graphql부분(2)
  const [addUser, { data }] = useMutation(ADD_USER);

  const onFinish = ({ first, last, email }) => {
    // graphql부분(3)
    addUser({ variables: { first, last, email } });
    setResetKey(resetKey + 1);
  };
  return (
    <Form
      onFinish={onFinish}
      labelCol={{ span: 4 }}
      wrapperCol={{ span: 20 }}
      initialValues={{
        first: `dao${new Date().getTime()}`,
        last: `lang${new Date().getTime()}`,
        email: `daolang${new Date().getTime()}@gmail.com`,
      }}
      key={resetKey}
    >
      <Form.Item
        name="first"
        label="first"
      >
        <Input />
      </Form.Item>
      <Form.Item
        name="last"
        label="last"
      >
        <Input />
      </Form.Item>
      <Form.Item
        name="email"
        label="Email"
      >
        <Input />
      </Form.Item>
      <Form.Item>
        <Button
          type="primary"
          htmlType="submit"
        >
          Submit
        </Button>
      </Form.Item>
    </Form>
  );
};

export default Page;
```

Query

```jsx
import React, { useCallback } from "react";
import { useQuery, gql } from "@apollo/client";
import { useDispatch, useSelector } from "react-redux";
import { setCount } from "./redux";
import { Table } from "antd";

// graphql(1)
const GET_USERS = gql`
  query Users {
    users {
      key: _id
      name {
        first
        last
      }
      email
    }
  }
`;

const columns = [
  {
    title: "Id",
    dataIndex: "key",
    key: "key",
  },
  {
    title: "First",
    dataIndex: "name",
    key: "name",
    render: (item) => item.first,
  },
  {
    title: "Last",
    dataIndex: "name",
    key: "name",
    render: (item) => item.first,
  },
  {
    title: "Email",
    dataIndex: "email",
    key: "email",
  },
];

const Page = () => {
  // graphql(2)
  const { loading, error, data } = useQuery(GET_USERS);
  if (loading) return <p>Loading...</p>;
  if (error) return <p>Error :(</p>;

  return (
    <div>
      <Table
        columns={columns}
        dataSource={data.users}
      />
    </div>
  );
};

export default Page;
```
