# Structures

## Stack
```js
function Stack() {
  let items = [];
  // 添加元素
  this.push = (element) => (items = [...items, element]);
  // 删除元素
  this.pop = () => items.pop();
  // 返回顶元素
  this.peek = () => items[items.length - 1];
  // 是否为空
  this.isEmpty = () => items.length === 0;
  // 清空
  this.clear = () => (items = []);
  // 元素个数
  this.size = () => items.length;
  // 打印
  this.print = () => items;
}
```

## Queue

```js
function Queue() {
  let items = [];
  // 添加元素
  this.enqueue = (element) => (items = [...items, element]);
  // 删除元素
  this.dequeue = () => items.shift();
  // 第一个元素
  this.front = () => items[0];
  // 是否为空
  this.isEmpty = () => items.length === 0;
  // 元素个数
  this.size = () => items.length;
  // 打印
  this.print = () => items;
}

function PriorityQueue() {
  let items = [];
  // 添加元素
  this.enqueue = (element, priority) => {
    const obj = { element, priority };
    for (let i = 0; i < items.length; i++) {
      // 根据重要度插入到特定位置
      if (priority > items[i].priority) {
        return (items = [...items.slice(0, i), obj, ...items.slice(i)]);
      }
    }
    return (items = [...items, obj]);
    // 速度慢
    // items = [...items, obj].sort((a,b) => b.priority - a.priority)
  };
  // 删除元素
  this.dequeue = () => items.shift();
  // 第一个元素
  this.front = () => items[0];
  // 是否为空
  this.isEmpty = () => items.length === 0;
  // 元素个数
  this.size = () => items.length;
  // 打印
  this.print = () => items;
}
```

## LinkedList

```js
function LinkedList() {
  function Node(element) {
    this.element = element;
    this.next = null;
  }
  let length = 0;
  let head;

  // 尾部添加
  this.append = (value) => {
    const element = new Node(value);
    if (head) {
      let current = head;
      while (current.next) {
        current = current.next;
      }
      current.next = element;
    } else {
      head = element;
    }
    length++;
  };

  // 特定位置插入
  this.insert = (position, value) => {
    const element = new Node(value);
    let index = 1;
    let current = head;
    let tmp;

    while (index < position) {
      current = current.next;
      index++;
    }
    length++;
    if (position === 0) {
      element.next = current;
      head = element;
    } else {
      tmp = current.next;
      current.next = element;
      current.next.next = tmp;
    }
  };

  // 特定位置移除一项
  this.removeAt = (position) => {
    let index = 0;
    let previos;
    let current = head;

    while (index < position) {
      previos = current;
      current = current.next;
      index++;
    }
    previos.next = current.next;

    length--;
  };

  // 移除一项
  this.remove = (value) => {
    let previos;
    let current = head;
    while (current.element !== value) {
      previos = current;
      current = current.next;
    }
    previos.next = current.next;
    length--;
  };

  // 返回索引
  this.indexOf = (value) => {
    let index = 0;
    let current = head;
    while (current.element !== value) {
      current = current.next;
      index++;
    }
    return index;
  };

  // 是否为空
  this.isEmpty = () => length === 0;

  // 元素个数
  this.size = () => length;

  // 获取链头
  this.getHead = () => head.element;

  // 打印
  this.print = () => head;
}
```

## Set

```js
function NS_Set(a, b) {
  // 并集
  this.union = () => [...new Set(a.concat(b))];

  // 交集
  this.intersection = () => a.filter((e) => b.includes(e));

  // 差集
  this.difference = () => a.filter((e) => !b.includes(e));

  // 子集
  this.subset = () => b.every((e) => a.includes(e));
}
```

## Tree

```js
function BinarySearchTree() {
  function Node(element) {
    this.element = element;
    this.left = null;
    this.right = null;
  }
  let root;

  // 添加值
  this.insert = (element) => {
    const node = new Node(element);
    let current = root;

    if (!root) {
      return (root = node);
    }

    while (current.element !== node.element) {
      if (current.element > node.element) {
        current.left ? (current = current.left) : (current.left = node);
      }

      if (current.element < node.element) {
        current.right ? (current = current.right) : (current.right = node);
      }
    }
    return ''
  };

  // 搜索
  this.search = (element) => {
    let current = root;

    while (current) {
      if (current.element === element) {
        return true;
      }
      current = current[current.element > element ? 'left' : 'right'];
    }

    return false;
  };

  // 最小值
  this.min = (current = root) => {
    while (current.left) {
      current = current.left;
    }
    return current.element;
  };

  // 最大值
  this.max = (current = root) => {
    while (current.right) {
      current = current.right;
    }
    return current.element;
  };

  // 删除元素
  this.remove = (element, isAll = true) => {
    let current = root;
    let previos = {
      node: root,
      type: 'left',
    };

    if (root.element === element) {
      return console.log('root无法删除');
    }

    while (current) {
      if (current.element === element) {
        // 删除整个节点或者无子元素
        if (isAll || (current.left === null && current.right === null)) {
          return (previos.node[previos.type] = null);
        }

        if (current.left !== null && current.right !== null) {
          // 两个子节点
          // 替换为右侧节点中最小值并保持原先的lr
          // const value = this.min(current.right)
          // 替换为左侧节点中最大值并保持原先的lr
          const value = this.max(current.left);
          this.remove(value, true);
          previos.node[previos.type].element = value;
        } else {
          // 一个子节点
          previos.node[previos.type] = current[current.left || current.right];
        }
        return '';
        //
      }
      previos = {
        node: current,
        type: current.element > element ? 'left' : 'right',
      };
      current = current[current.element > element ? 'left' : 'right'];
    }

    return false;
  };

  // 中序遍历
  this.inOrderTraverse = () => {
    function fn(node) {
      if (node.left) {
        fn(node.left);
      }
      console.log(node.element);
      if (node.right) {
        fn(node.right);
      }
    }
    fn(root);
  };
  // 先序遍历
  this.preOrderTraverse = () => {
    function fn(node) {
      console.log(node.element);
      if (node.left) {
        fn(node.left);
      }

      if (node.right) {
        fn(node.right);
      }
    }
    fn(root);
  };
  // 后序遍历7
  this.postOrderTraverse = () => {
    function fn(node) {
      if (node.left) {
        fn(node.left);
      }
      if (node.right) {
        fn(node.right);
      }
      console.log(node.element);
    }
    fn(root);
  };

  this.print = () => console.log(JSON.stringify(root, null, 2));
}
```
