import WebSocket from 'ws';

const GMO_WS_URL = 'wss://api.coin.z.com/ws/public/v1';

interface SubscriptionRequest {
  command: 'subscribe' | 'unsubscribe';
  channel: string;
  symbol: string;
}

interface TestResult {
  pattern: string;
  tradeCount: number;
  orderbookCount: number;
  tickerCount: number;
  totalMessages: number;
}

class GmoWebSocketTest {
  private ws: WebSocket | null = null;
  private messageCount = 0;
  private tradeCount = 0;
  private orderbookCount = 0;
  private tickerCount = 0;
  private commandResponseCount = 0;
  private unknownCount = 0;
  private testPattern: string = 'pattern1';

  constructor(pattern: string = 'pattern1') {
    this.testPattern = pattern;
  }

  async run(): Promise<TestResult> {
    console.log(`\n${'='.repeat(50)}`);
    console.log(`🚀 GMO WebSocket Test - Pattern: ${this.testPattern}`);
    console.log(`${'='.repeat(50)}`);
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
        const result = this.getResults();
        this.printResults();
        resolve(result);
      });

      // Auto-close after 30 seconds
      setTimeout(() => {
        if (this.ws) {
          console.log('\n⏱️  30 second timeout reached, closing connection...');
          this.ws.close();
        }
      }, 30000);
    });
  }

  private sendSubscriptions(): void {
    console.log(`📋 Subscription Pattern: ${this.testPattern}\n`);

    switch (this.testPattern) {
      case 'pattern1':
        // Original pattern: trades first, then orderbooks
        this.sendSubscription('trades', 'BTC');
        setTimeout(() => {
          this.sendSubscription('orderbooks', 'BTC');
        }, 2000);
        setTimeout(() => {
          this.sendSubscription('ticker', 'BTC');
        }, 4000);
        setTimeout(() => {
          this.sendSubscription('trades', 'BTC_JPY');
        }, 6000);
        break;

      case 'pattern2':
        // Current Java pattern: orderbooks first, then trades
        this.sendSubscription('orderbooks', 'BTC_JPY');
        setTimeout(() => {
          this.sendSubscription('orderbooks', 'BTC');
        }, 2000);
        setTimeout(() => {
          this.sendSubscription('trades', 'BTC');
        }, 4000);
        break;

      case 'pattern3':
        // Trades only (minimal)
        this.sendSubscription('trades', 'BTC');
        setTimeout(() => {
          this.sendSubscription('trades', 'BTC_JPY');
        }, 2000);
        break;

      case 'pattern4':
        // All trades immediately, then orderbooks
        this.sendSubscription('trades', 'BTC');
        this.sendSubscription('trades', 'BTC_JPY');
        setTimeout(() => {
          this.sendSubscription('orderbooks', 'BTC');
        }, 2000);
        setTimeout(() => {
          this.sendSubscription('orderbooks', 'BTC_JPY');
        }, 4000);
        break;

      case 'pattern5':
        // Very aggressive: trades with minimal delay
        this.sendSubscription('trades', 'BTC');
        setTimeout(() => {
          this.sendSubscription('trades', 'BTC_JPY');
        }, 500);
        setTimeout(() => {
          this.sendSubscription('orderbooks', 'BTC');
        }, 1000);
        break;

      case 'pattern6':
        // Java新パターン: trades first, then orderbooks with 1sec delay
        this.sendSubscription('trades', 'BTC');
        setTimeout(() => {
          this.sendSubscription('orderbooks', 'BTC_JPY');
        }, 1000);
        setTimeout(() => {
          this.sendSubscription('orderbooks', 'BTC');
        }, 3000);
        setTimeout(() => {
          this.sendSubscription('trades', 'BTC_JPY');
        }, 5000);
        break;

      default:
        console.log(`❌ Unknown pattern: ${this.testPattern}`);
    }
  }

  private sendSubscription(channel: string, symbol: string): void {
    if (!this.ws) return;

    const request: SubscriptionRequest = {
      command: 'subscribe',
      channel,
      symbol,
    };

    const requestJson = JSON.stringify(request);
    console.log(`📡 [${this.getTimestamp()}] Subscribing to ${channel} channel: ${symbol}`);

    this.ws.send(requestJson);
  }

  private processMessage(message: string): void {
    this.messageCount++;

    try {
      const json = JSON.parse(message);

      // Handle command response
      if (json.command) {
        this.commandResponseCount++;
        console.log(`✓ [${this.getTimestamp()}] Command response: ${json.command}`);
        return;
      }

      // Handle channel data
      if (json.channel) {
        const channel = json.channel;

        if (channel === 'trades') {
          this.tradeCount++;
          const symbol = json.symbol || 'unknown';
          const tradesCount = json.trades?.length || 0;
          console.log(
            `\n🎉 [${this.getTimestamp()}] TRADES MESSAGE RECEIVED! #${this.tradeCount} (${symbol}, ${tradesCount} items)`
          );
          if (this.tradeCount <= 1) {
            console.log(JSON.stringify(json, null, 2));
          }
          console.log('');
        } else if (channel === 'orderbooks') {
          this.orderbookCount++;
          if (this.orderbookCount <= 2) {
            const symbol = json.symbol;
            const bidsCount = json.bids?.length || 0;
            const asksCount = json.asks?.length || 0;
            console.log(
              `📊 [${this.getTimestamp()}] Orderbook #${this.orderbookCount} - ${symbol} (bids: ${bidsCount}, asks: ${asksCount})`
            );
          }
        } else if (channel === 'ticker') {
          this.tickerCount++;
          if (this.tickerCount <= 2) {
            const symbol = json.symbol;
            const last = json.last;
            const bid = json.bid;
            const ask = json.ask;
            console.log(
              `💹 [${this.getTimestamp()}] Ticker #${this.tickerCount} - ${symbol} | Last: ${last} | Bid: ${bid} | Ask: ${ask}`
            );
          }
        } else {
          this.unknownCount++;
          console.log(`❓ [${this.getTimestamp()}] Unknown channel: ${channel}`);
        }
      } else {
        this.unknownCount++;
        const preview = message.length > 100 ? message.substring(0, 100) + '...' : message;
        console.log(`❓ [${this.getTimestamp()}] Message without channel field: ${preview}`);
      }
    } catch (error) {
      console.error(`❌ Error processing message: ${(error as Error).message}`);
      const preview = message.length > 200 ? message.substring(0, 200) + '...' : message;
      console.error(`   Message: ${preview}`);
    }
  }

  private printResults(): void {
    console.log('\n========== TEST RESULTS ==========');
    console.log(`Pattern: ${this.testPattern}`);
    console.log(`Total messages: ${this.messageCount}`);
    console.log(`Command responses: ${this.commandResponseCount}`);
    console.log(`Trade messages: ${this.tradeCount}`);
    console.log(`Orderbook messages: ${this.orderbookCount}`);
    console.log(`Ticker messages: ${this.tickerCount}`);
    console.log(`Unknown messages: ${this.unknownCount}`);
    console.log('================================\n');

    if (this.tradeCount > 0) {
      console.log('✅ SUCCESS: Trade channel is working!');
      console.log(`   Received ${this.tradeCount} trade messages`);
    } else {
      console.log('❌ FAILURE: Trade channel is NOT sending data');
    }
  }

  private getResults(): TestResult {
    return {
      pattern: this.testPattern,
      tradeCount: this.tradeCount,
      orderbookCount: this.orderbookCount,
      tickerCount: this.tickerCount,
      totalMessages: this.messageCount,
    };
  }

  private getTimestamp(): string {
    return new Date().toISOString().split('T')[1].split('Z')[0];
  }
}

// Run all test patterns
async function runAllTests(): Promise<void> {
  const patterns = ['pattern1', 'pattern2', 'pattern3', 'pattern4', 'pattern5', 'pattern6'];
  const results: TestResult[] = [];

  for (const pattern of patterns) {
    const test = new GmoWebSocketTest(pattern);
    const result = await test.run();
    results.push(result);

    // Wait between tests
    await new Promise((resolve) => setTimeout(resolve, 3000));
  }

  // Print summary
  console.log('\n' + '='.repeat(60));
  console.log('📊 SUMMARY OF ALL PATTERNS');
  console.log('='.repeat(60));
  console.log('Pattern    | Trades | Orderbooks | Total Messages');
  console.log('-'.repeat(60));

  results.forEach((result) => {
    const pattern = result.pattern.padEnd(10);
    const trades = String(result.tradeCount).padEnd(6);
    const orderbooks = String(result.orderbookCount).padEnd(10);
    const total = String(result.totalMessages);
    console.log(`${pattern} | ${trades} | ${orderbooks} | ${total}`);
  });

  console.log('='.repeat(60));
  console.log('\n💡 Analysis:');
  results.forEach((result) => {
    if (result.tradeCount > 0) {
      console.log(`✅ ${result.pattern}: TRADES WORKING (${result.tradeCount} messages)`);
    } else {
      console.log(`❌ ${result.pattern}: NO TRADES`);
    }
  });
}

// Main execution
const args = process.argv.slice(2);
if (args.length > 0 && args[0] === '--all') {
  // Run all patterns
  runAllTests().catch((error) => {
    console.error('Fatal error:', error);
    process.exit(1);
  });
} else {
  // Run single pattern
  const pattern = args.length > 0 ? args[0] : 'pattern1';
  const test = new GmoWebSocketTest(pattern);
  test.run().catch((error) => {
    console.error('Fatal error:', error);
    process.exit(1);
  });
}
