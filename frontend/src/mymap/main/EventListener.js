let _eventId = 0;

export class EventListener {
  eventList = {};
  stopEventList = {};

  constructor({ event = {} } = {}) {
    this.eventList = {};
    this.stopEventList = {};

    for (const type in event) {
      this.addEventListener(type, event[type]);
    }
  }

  // 事件处理
  handleEventListener(type, data = {}) {
    if (this.eventList[type]) {
      for (const eventId in this.eventList[type]) {
        let func = this.eventList[type][eventId];
        if (func instanceof Function && !this.stopEventList[type][eventId]) {
          try {
            func({
              type: type,
              target: this,
              data: data,
            });
          } catch (error) {
            console.error(error);
          }
        }
      }
    }
  }

  // 停止事件处理
  stopEventListener(type, eventId) {
    if (eventId) {
      this.stopEventList[type][eventId] = true;
    } else {
      for (const eventId2 in this.stopEventList[type]) {
        this.stopEventList[type][eventId2] = true;
      }
    }
  }

  // 恢复事件处理
  resumeEventListener(type, eventId) {
    if (eventId) {
      this.stopEventList[type][eventId] = false;
    } else {
      for (const eventId2 in this.stopEventList[type]) {
        this.stopEventList[type][eventId2] = false;
      }
    }
  }

  // 添加事件
  addEventListener(type, func) {
    const eventId = ++_eventId;
    if (!this.eventList[type]) {
      this.eventList[type] = {};
      this.stopEventList[type] = {};
    }
    this.eventList[type][eventId] = func;
    this.stopEventList[type][eventId] = false;
    return eventId;
  }

  // 删除事件
  removeEventListener(type, eventId) {
    if (!type) {
      this.eventList = {};
      this.stopEventList = {};
    } else if (!eventId) {
      this.eventList[type] = {};
      this.stopEventList[type] = {};
    } else if (this.eventList[type]) {
      delete this.eventList[type][eventId];
      delete this.stopEventList[type][eventId];
    }
  }
}
