import WebSocket from 'ws';

const GMO_WS_URL = 'wss://api.coin.z.com/ws/public/v1';

interface SubscriptionRequest {
  command: 'subscribe' | 'unsubscribe';
  channel: string;
  symbol: string;
}

class GmoWebSocketTest {
  private ws: WebSocket | null = null;
  private messageCount = 0;
  private tradeCount = 0;
  private orderbookCount = 0;
  private tickerCount = 0;
  private commandResponseCount = 0;
  private unknownCount = 0;

  async run(): Promise<void> {
    console.log('🚀 Starting GMO WebSocket Trade Channel Test...');
    console.log(`URL: ${GMO_WS_URL}`);
    console.log('');

    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(GMO_WS_URL);

      this.ws.on('open', () => {
        console.log('✅ WebSocket session established\n');
        this.sendSubscriptions();
      });

      this.ws.on('message', (data: WebSocket.Data) => {
        this.processMessage(data.toString());
      });

      this.ws.on('error', (error: Error) => {
        console.error(`❌ WebSocket error: ${error.message}`);
        reject(error);
      });

      this.ws.on('close', () => {
        console.log('\n🔚 WebSocket connection closed');
        this.printResults();
        resolve();
      });

      // Auto-close after 60 seconds
      setTimeout(() => {
        if (this.ws) {
          console.log('\n⏱️  60 second timeout reached, closing connection...');
          this.ws.close();
        }
      }, 60000);
    });
  }

  private sendSubscriptions(): void {
    // Subscribe to trades channel first
    this.sendSubscription('trades', 'BTC');

    // Subscribe to orderbooks after 2 seconds
    setTimeout(() => {
      this.sendSubscription('orderbooks', 'BTC');
    }, 2000);

    // Subscribe to ticker after 4 seconds
    setTimeout(() => {
      this.sendSubscription('ticker', 'BTC');
    }, 4000);
  }

  private sendSubscription(channel: string, symbol: string): void {
    if (!this.ws) return;

    const request: SubscriptionRequest = {
      command: 'subscribe',
      channel,
      symbol,
    };

    const requestJson = JSON.stringify(request);
    console.log(`📡 Subscribing to ${channel} channel: ${symbol}`);
    console.log(`   Request: ${requestJson}\n`);

    this.ws.send(requestJson);
  }

  private processMessage(message: string): void {
    this.messageCount++;

    try {
      const json = JSON.parse(message);

      // Handle command response
      if (json.command) {
        this.commandResponseCount++;
        console.log(`✓ Command response: ${json.command}`);
        return;
      }

      // Handle channel data
      if (json.channel) {
        const channel = json.channel;

        if (channel === 'trades') {
          this.tradeCount++;
          console.log(`\n🎉 TRADES MESSAGE RECEIVED! #${this.tradeCount}`);
          console.log(JSON.stringify(json, null, 2));
          console.log('');
        } else if (channel === 'orderbooks') {
          this.orderbookCount++;
          if (this.orderbookCount <= 3) {
            const symbol = json.symbol;
            const bidsCount = json.bids?.length || 0;
            const asksCount = json.asks?.length || 0;
            console.log(
              `📊 Orderbook #${this.orderbookCount} - ${symbol} (bids: ${bidsCount}, asks: ${asksCount})`
            );
          }
        } else if (channel === 'ticker') {
          this.tickerCount++;
          if (this.tickerCount <= 3) {
            const symbol = json.symbol;
            const last = json.last;
            const bid = json.bid;
            const ask = json.ask;
            console.log(
              `💹 Ticker #${this.tickerCount} - ${symbol} | Last: ${last} | Bid: ${bid} | Ask: ${ask}`
            );
          }
        } else {
          this.unknownCount++;
          console.log(`❓ Unknown channel: ${channel}`);
        }
      } else {
        this.unknownCount++;
        const preview = message.length > 100 ? message.substring(0, 100) + '...' : message;
        console.log(`❓ Message without channel field: ${preview}`);
      }
    } catch (error) {
      console.error(`❌ Error processing message: ${(error as Error).message}`);
      const preview = message.length > 200 ? message.substring(0, 200) + '...' : message;
      console.error(`   Message: ${preview}`);
    }
  }

  private printResults(): void {
    console.log('\n========== TEST RESULTS ==========');
    console.log(`Total messages: ${this.messageCount}`);
    console.log(`Command responses: ${this.commandResponseCount}`);
    console.log(`Trade messages: ${this.tradeCount}`);
    console.log(`Orderbook messages: ${this.orderbookCount}`);
    console.log(`Ticker messages: ${this.tickerCount}`);
    console.log(`Unknown messages: ${this.unknownCount}`);
    console.log('================================\n');

    if (this.tradeCount > 0) {
      console.log('✅ SUCCESS: Trade channel is working!');
      console.log(
        '   GMO API is sending real-time trade data through the trades channel.'
      );
    } else {
      console.log('❌ FAILURE: Trade channel is NOT sending data from GMO API');
      console.log(
        '   This confirms the issue - GMO public API does not send trade data'
      );
      console.log(
        '   Alternative: Use ticker channel for price updates instead'
      );
    }

    process.exit(this.tradeCount > 0 ? 0 : 1);
  }
}

// Run the test
const test = new GmoWebSocketTest();
test.run().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
