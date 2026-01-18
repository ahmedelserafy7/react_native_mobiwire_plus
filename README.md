# react-native-mobiprint3plus

## Getting started

`$ npm install react-native-mobiprint3plus --save`

### Mostly automatic installation

`$ react-native link react-native-mobiprint3plus`

## Usage
```javascript
import Mobiprint3plus from 'react-native-mobiprint3plus';

// TODO: What to do with the module?
  const print = () => {
    console.log("PRINTING");
    //inital required step
    Mobiprint3plus.connectPOS();
    //second step
    Mobiprint3plus.addTextToPrint(
      /* Line of text */ "1",
      /* Second line of text */ null,
      /* FontSize */ 50,
      /* isBold */ false,
      /* isUnderLine */ false,
      /* Align 1>center , 0>left , 2>right */ 1
    );
    Mobiprint3plus.addTextToPrint("2", null, 50, false, false, 1);
    Mobiprint3plus.printLine();
    Mobiprint3plus.addTextToPrint("3", null, 50, false, true, 0);
    Mobiprint3plus.addTextToPrint("4", null, 50, false, true, 0);
    Mobiprint3plus.printLine();
    Mobiprint3plus.addTextToPrint("5", null, 50, true, true, 2);
    Mobiprint3plus.addTextToPrint("6", null, 50, true, true, 2);
    //final step
    Mobiprint3plus.print();
  };
```
