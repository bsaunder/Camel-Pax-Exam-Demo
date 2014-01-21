/**
 * 
 */
package com.walmart.mqm.test.paxexam.util;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Camel Processor that Throws a java.net.ConnectException.
 * 
 * @author Bryan Saunders <bsaunder@redhat.com>
 * 
 */
public class ExceptionProcessor implements Processor {

    /**
     * Default Error Message.
     */
    public static final String DEF_ERR_MSG = "An Error Occured";

    /**
     * Default Error.
     */
    private Exception exception;

    /**
     * Default Constructor. Sets the Exception to a general Exception with the Message defined in DEF_ERR_MSG.
     */
    public ExceptionProcessor() {
        this(new Exception(ExceptionProcessor.DEF_ERR_MSG));
    }

    /**
     * Constructor that Sets the Exception to Throw.
     * 
     * @param exceptionToThrow
     *            Exception to use.
     */
    public ExceptionProcessor(Exception exceptionToThrow) {
        this.exception = exceptionToThrow;
    }

    /**
     * Get the Exception To Throw.
     * 
     * @return the exceptionToThrow
     */
    public Exception getException() {
        return this.exception;
    }

    /**
     * Set the Exception To Throw.
     * 
     * @param exceptionToThrow
     *            the exceptionToThrow to set
     */
    public void setException(Exception exceptionToThrow) {
        this.exception = exceptionToThrow;
    }

    /**
     * Throws the currently set Exception.
     */
    @Override
    public void process(Exchange arg0) throws Exception {
        throw this.exception;
    }

}

