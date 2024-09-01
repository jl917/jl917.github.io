import { CSSProperties } from "react";

const style: CSSProperties = {
  width: "100%",
  height: "auto",
};

const Giphy = ({ children }) => {
  return (
    <iframe
      style={style}
      src={`https://giphy.com/embed/${children.toString()}`}
      allowFullScreen
    />
  );
};

export default Giphy;
